/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.client.AgentStreamClient;
import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.service.core.RequestQueueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * ⭐ Consumer 统一流式对话控制器
 * <p>
 * 对前端完全透明，不暴露后端 Agent 架构
 * <p>
 * 流程：前端 → Consumer → Router(路由决策) → Agent(SSE) → Consumer(转发) → 前端
 * <p>
 * SSE 事件类型：
 * - event: waiting   - 等待路由决策中（5 秒超时）
 * - event: routed    - 路由信息（告诉前端当前调用的是哪个 Agent）
 * - event: thinking  - AI 思考过程
 * - event: tool_call - 工具调用请求
 * - event: tool_result - 工具执行结果
 * - event: response  - 最终回复
 * - event: timeout   - 超时（决策获取失败，不再获取推理过程）
 * - event: error     - 错误信息
 * - event: done      - 完成信号
 */
@RestController
@RequestMapping("/api/math/stream")
@Slf4j
public class StreamChatController {
    
    private static final Logger logger = LoggerFactory.getLogger(StreamChatController.class);
    
    /** ⭐ SSE 等待决策的最大超时时间（毫秒） */
    private static final long DECISION_TIMEOUT_MS = 60000;  // 60 秒超时
    
    /** ⭐ SSE 事件缓冲区 Redis key 前缀 */
    private static final String SSE_BUFFER_PREFIX = "sse:buffer:";
    /** ⭐ SSE 事件缓冲区 TTL（秒）— 5 分钟，足够用户刷新页面重连 */
    private static final long SSE_BUFFER_TTL_SECONDS = 300;
    
    private final RouterClient routerClient;
    private final AgentStreamClient agentStreamClient;
    private final StringRedisTemplate redisTemplate;
    private final RequestQueueService requestQueueService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StreamChatController(
            RouterClient routerClient,
            AgentStreamClient agentStreamClient,
            RequestQueueService requestQueueService,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.routerClient = routerClient;
        this.agentStreamClient = agentStreamClient;
        this.requestQueueService = requestQueueService;
        this.redisTemplate = redisTemplate;
    }
    
    /**
     * ⭐ SSE 流式对话接口 - 统一入口
     * <p>
     * GET /api/math/stream/chat?message=xxx&requestId=xxx
     * <p>
     * 前端无需知道后端 Agent 架构，Consumer 自动完成路由
     * <p>
     * 流程：
     * 1. 从 Redis 获取路由决策（chat 接口已触发并存入 Redis）
     * 2. 获取 agentName
     * 3. 调用 Agent SSE 并实时转发推理过程
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamChat(
            @RequestParam String message,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false, defaultValue = "true") boolean showThinking,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String model,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            jakarta.servlet.http.HttpServletResponse response) {
        
        logger.info("[StreamChat] 收到流式请求: messageLength={}, requestId={}, showThinking={}, lastEventId={}", 
                message != null ? message.length() : 0, requestId, showThinking, lastEventId);

        // 2. 初始化 SSE 响应
        try {
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setStatus(HttpServletResponse.SC_OK);
            response.flushBuffer();
        } catch (Exception e) {
            logger.error("[StreamChat] 初始化 SSE 失败: {}", e.getMessage());
            return;
        }
        
        // ⭐ 2.5 断线续传：从 Redis 读取已缓存的事件并补发
        boolean resumed = false;
        if (requestId != null && lastEventId != null && !lastEventId.isBlank()) {
            resumed = resumeFromBuffer(response, requestId, lastEventId);
        }
        
        // 3. ⭐ 从 Redis 获取路由决策（chat 接口已触发 Router 决策并存入 Redis）
        Map<String, Object> decision = getRoutingDecisionFromRedis(requestId, response);
        
        // 如果获取决策失败，直接结束
        if (decision == null || !decision.containsKey("agentName")) {
            logger.warn("[StreamChat] 路由决策获取失败，无法继续");
            sendErrorEvent(response, "路由决策获取失败，请稍后重试");
            return;
        }
        
        String agentName = (String) decision.get("agentName");
        Double confidence = (Double) decision.getOrDefault("confidence", 0.0);
        logger.info("[StreamChat] 路由决策: agentName={}, confidence={}", agentName, confidence);
        
        // ⭐ 检查是否有任务拆解的 SSE 事件（多 Agent 推理过程）
        if (requestId != null) {
            String eventsKey = "routing:sse:events:" + requestId;
            long eventCount = redisTemplate != null ? redisTemplate.opsForList().size(eventsKey) : 0;
            if (eventCount > 0) {
                logger.info("[StreamChat] 发现多 Agent SSE 事件共 {} 条, 从 Redis 直接发送", eventCount);
                forwardEventsFromRedis(response, eventsKey);
                return;
            }
        }

        // ⭐ 4. 检查流式支持（单 Agent）
        if (!agentStreamClient.isStreamingSupported(agentName)) {
            logger.warn("[StreamChat] Agent 不支持流式: {}, 回退到非流式", agentName);
            sendErrorEvent(response, "Agent 不支持流式响应");
            return;
        }
        
        // ⭐ 5. 排队等待 LLM 槽位（请求排队核心逻辑）
        if (requestId != null && !requestId.isBlank()) {
            RequestQueueService.SlotResult slotResult = requestQueueService.tryAcquireWithQueue(requestId);
            
            switch (slotResult) {
                case QUEUE_FULL:
                    logger.warn("[StreamChat] 队列已满，拒绝请求: requestId={}", requestId);
                    sendErrorEvent(response, "系统繁忙，请稍后再试");
                    return;
                    
                case QUEUED:
                    // ⭐ 发送排队事件
                    int pos = requestQueueService.getQueuePosition(requestId);
                    long estWait = pos * 5000L; // 估计等待时间 ≈ 位置 × 5s
                    sendQueueEvent(response, pos, estWait);
                    
                    // ⭐ 阻塞等待槽位（complete() 会按 FIFO 顺序唤醒）
                    boolean acquired = requestQueueService.waitForSlot(requestId);
                    if (acquired) {
                        sendProcessingEvent(response);
                    } else {
                        sendTimeoutEvent(response, "排队超时，请稍后重试");
                        return;
                    }
                    break;
                    
                case ACQUIRED:
                    // 立即获取到槽位
                    sendProcessingEvent(response);
                    break;
            }
        }
        
        // 6. 调用 Agent SSE 并实时转发
        String agentUrl = agentStreamClient.getStreamUrl(agentName);
        logger.info("[StreamChat] 调用 Agent SSE: url={}", agentUrl);
        
        // 添加 ?message=xxx&showThinking=true
        String fullUrl = agentUrl + "?message=" + encodeUrl(message) + "&showThinking=" + showThinking;
        
        try {
            // ⭐ 使用带缓存版本的 forwardSSE（传递 requestId 用于事件缓存）
            forwardSSE(response, fullUrl, requestId, null);
        } finally {
            // ⭐ 释放 LLM 槽位（如果之前获取了槽位）
            if (requestId != null && !requestId.isBlank()) {
                requestQueueService.complete(requestId);
            }
        }
    }
    
    /**
     * ⭐ POST 版本（支持更长的消息）
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamChatPost(
            @RequestBody Map<String, Object> request,
            HttpServletResponse httpResponse) {
        
        String message = (String) request.getOrDefault("message", request.get("question"));
        String requestId = (String) request.getOrDefault("requestId", null);
        boolean showThinking = request.containsKey("showThinking")
                ? (Boolean) request.get("showThinking") 
                : true;
        
        streamChat(message, requestId, showThinking, null, null, null, httpResponse);
    }

    /**
     * ⭐ 从 Redis 获取路由决策
     * <p>
     * chat 接口已触发 Router 决策并存入 Redis，SSE 接口从这里获取
     * 流程：
     * 1. 必须提供 requestId
     * 2. 从 Redis 阻塞等待决策结果（最多 5 秒）
     * 3. 如果超时，返回 null
     */
    private Map<String, Object> getRoutingDecisionFromRedis(
            String requestId,
            HttpServletResponse response) {
        try {
            // SSE 接口必须从 Redis 阻塞等待决策
            if (requestId == null || requestId.isBlank()) {
                logger.warn("[StreamChat] SSE 无 requestId，无法从 Redis 获取决策");
                return null;
            }
            
            logger.info("[StreamChat] 从 Redis 获取路由决策: requestId={}, timeout={}ms", 
                    requestId, DECISION_TIMEOUT_MS);
            
            // 通知前端正在等待路由
            sendWaitingEvent(response);
            
            // 从 Redis 阻塞等待决策（chat 接口已触发 Router 决策）
            Map<String, Object> decision = routerClient.waitForDecisionFromRedis(requestId, DECISION_TIMEOUT_MS);
            
            if (decision != null && decision.containsKey("agentName")) {
                logger.info("[StreamChat] Redis 决策获取成功: agentName={}, confidence={}", 
                        decision.get("agentName"), decision.get("confidence"));
                return decision;
            }
            
            logger.warn("[StreamChat] Redis 等待决策超时（{}ms），决策不可用", DECISION_TIMEOUT_MS);
            return null;
            
        } catch (Exception e) {
            logger.error("[StreamChat] 从 Redis 获取路由决策失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * ⭐ 发送等待事件给前端
     */
    private void sendWaitingEvent(HttpServletResponse response) {
        try {
            String event = String.format("event: waiting\ndata: {\"type\":\"waiting\",\"content\":\"%s\"}\n\n", 
                    escapeForSSE("正在分析意图..."));
            response.getOutputStream().write(event.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
        } catch (Exception e) {
            logger.debug("[StreamChat] 发送等待事件失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ 发送错误事件给前端
     */
    private void sendErrorEvent(jakarta.servlet.http.HttpServletResponse response, String errorMessage) {
        if (response.isCommitted()) {
            logger.debug("[StreamChat] 响应已提交，跳过错误事件: {}", errorMessage);
            return;
        }
        try {
            String event = String.format("event: error\ndata: {\"type\":\"error\",\"content\":\"%s\"}\n\n", 
                    escapeForSSE(errorMessage));
            response.getOutputStream().write(event.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
        } catch (Exception e) {
            logger.error("[StreamChat] 发送错误事件失败: {}", e.getMessage());
        }
    }
    
    // ==================== 排队相关 SSE 事件 ====================
    
    /**
     * ⭐ 发送排队事件
     */
    private void sendQueueEvent(HttpServletResponse response, int position, long estimatedWaitMs) {
        try {
            String json = String.format(
                    "{\"type\":\"queued\",\"position\":%d,\"estimatedWaitMs\":%d}",
                    position, estimatedWaitMs);
            String event = "event: queued\ndata: " + json + "\n\n";
            response.getOutputStream().write(event.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
            logger.info("[StreamChat] ⏳ 排队事件: position={}, estimatedWaitMs={}", position, estimatedWaitMs);
        } catch (Exception e) {
            logger.debug("[StreamChat] 发送排队事件失败: {}", e.getMessage());
        }
    }
    
    /**
     * ⭐ 发送排队位置更新事件
     */
    private void sendQueuePositionEvent(HttpServletResponse response, int position, long estimatedWaitMs) {
        try {
            String json = String.format(
                    "{\"type\":\"queue_position\",\"position\":%d,\"estimatedWaitMs\":%d}",
                    position, estimatedWaitMs);
            String event = "event: queue_position\ndata: " + json + "\n\n";
            response.getOutputStream().write(event.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
        } catch (Exception e) {
            logger.debug("[StreamChat] 发送位置更新事件失败: {}", e.getMessage());
        }
    }
    
    /**
     * ⭐ 发送处理中事件（槽位已分配）
     */
    private void sendProcessingEvent(HttpServletResponse response) {
        try {
            String event = "event: processing\ndata: {\"type\":\"processing\"}\n\n";
            response.getOutputStream().write(event.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
            logger.info("[StreamChat] ▶️ 处理中事件");
        } catch (Exception e) {
            logger.debug("[StreamChat] 发送处理中事件失败: {}", e.getMessage());
        }
    }
    
    /**
     * ⭐ 从 Redis 缓冲区读取已缓存的事件并补发（断线续传）。
     * <p>
     * 前端断开后重新连接时，通过 {@code lastEventId} 定位断点，
     * 从 Redis Hash {@code sse:buffer:{requestId}} 中读取 seqNo > lastEventId 的事件并补发。
     * </p>
     *
     * @param response   SSE 响应
     * @param requestId  请求 ID
     * @param lastEventId 前端最后收到的事件 ID
     * @return true 表示已成功补发历史事件
     */
    private boolean resumeFromBuffer(HttpServletResponse response, String requestId, String lastEventId) {
        String redisKey = SSE_BUFFER_PREFIX + requestId;
        if (redisTemplate == null) return false;

        try {
            // 检查是否存在缓冲区
            Boolean hasKey = redisTemplate.hasKey(redisKey);
            if (hasKey == null || !hasKey) {
                logger.info("[StreamChat] 无缓冲区, lastEventId={} 被忽略", lastEventId);
                return false;
            }

            long lastSeq;
            try {
                lastSeq = Long.parseLong(lastEventId);
            } catch (NumberFormatException e) {
                logger.warn("[StreamChat] 无效的 lastEventId: {}", lastEventId);
                return false;
            }

            // 获取所有字段名（事件序号）
            java.util.Set<Object> fields = redisTemplate.opsForHash().keys(redisKey);
            if (fields == null || fields.isEmpty()) return false;

            // 找出大于 lastEventId 的序号并排序
            java.util.List<Long> pendingSeqs = new java.util.ArrayList<>();
            for (Object field : fields) {
                try {
                    long seq = Long.parseLong(field.toString());
                    if (seq > lastSeq) pendingSeqs.add(seq);
                } catch (NumberFormatException ignored) {}
            }

            if (pendingSeqs.isEmpty()) {
                logger.info("[StreamChat] 无待补发事件: requestId={}, lastEventId={}", requestId, lastEventId);
                return false;
            }

            java.util.Collections.sort(pendingSeqs);
            logger.info("[StreamChat] 🔄 断线续传: requestId={}, lastEventId={}, 补发事件数={}",
                    requestId, lastEventId, pendingSeqs.size());

            // 补发事件
            for (long seq : pendingSeqs) {
                Object data = redisTemplate.opsForHash().get(redisKey, String.valueOf(seq));
                if (data == null) continue;

                // 注入事件 ID
                String idLine = "id: " + seq + "\n";
                response.getOutputStream().write(idLine.getBytes(StandardCharsets.UTF_8));
                response.getOutputStream().write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                response.getOutputStream().flush();
            }

            return !pendingSeqs.isEmpty();

        } catch (Exception e) {
            logger.warn("[StreamChat] 断线续传异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * ⭐ 从 SSE 事件格式中提取 data: 部分。
     * <p>
     * 例如输入 {@code "event: thinking\ndata: {\"type\":\"thinking\"}\n"} → {@code "{\"type\":\"thinking\"}"}
     * </p>
     */
    private String extractDataPart(String sseEvent) {
        if (sseEvent == null || sseEvent.isBlank()) return null;
        for (String line : sseEvent.split("\n")) {
            if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                return data.isEmpty() ? null : data;
            }
        }
        return null;
    }

    /**
     * ⭐ 发送超时事件
     */
    private void sendTimeoutEvent(HttpServletResponse response, String message) {
        try {
            String json = String.format(
                    "{\"type\":\"timeout\",\"content\":\"%s\"}", escapeForSSE(message));
            String event = "event: timeout\ndata: " + json + "\n\n";
            response.getOutputStream().write(event.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
        } catch (Exception e) {
            logger.debug("[StreamChat] 发送超时事件失败: {}", e.getMessage());
        }
    }
    
    /**
     * ⭐ 转发 SSE 事件流（含事件 ID 注入 + Redis 缓冲区）
     * <p>
     * 每转发一条 SSE 事件，注入递增的事件 ID 并缓存到 Redis。
     * 前端断开后可通过 {@code lastEventId} 从断点续传。
     * </p>
     */
    private void forwardSSE(jakarta.servlet.http.HttpServletResponse response, String agentUrl) {
        forwardSSE(response, agentUrl, null, null);
    }

    /**
     * ⭐ 转发 SSE 事件流（含 requestId 用于缓存）。
     *
     * @param requestId 请求 ID（用于 Redis 事件缓存，null 表示不缓存）
     */
    private void forwardSSE(jakarta.servlet.http.HttpServletResponse response, String agentUrl,
                             String requestId, Long initialSeqNo) {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        
        try {
            // 构建请求
            URL url = new URL(agentUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Accept-Charset", "UTF-8");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(3600000); // 1小时超时
            
            // 传递认证 Token
            try {
                ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attributes != null) {
                    HttpServletRequest request = attributes.getRequest();
                    String authHeader = request.getHeader("Authorization");
                    if (authHeader != null) {
                        connection.setRequestProperty("Authorization", authHeader);
                    }
                }
            } catch (Exception e) {
                logger.debug("[StreamChat] 传递 Token 失败: {}", e.getMessage());
            }
            
            connection.connect();
            
            // 设置响应头
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            
            // ⭐ 使用行解析方式读取 SSE，以便注入事件 ID
            inputStream = connection.getInputStream();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(inputStream, StandardCharsets.UTF_8));
            
            String line;
            StringBuilder currentEvent = new StringBuilder();
            boolean hasData = false;
            long seqNo = (initialSeqNo != null) ? initialSeqNo : 1;
            String redisKey = (requestId != null && redisTemplate != null) 
                    ? SSE_BUFFER_PREFIX + requestId : null;
            
            while ((line = reader.readLine()) != null) {
                // 空行 = SSE 事件结束
                if (line.isEmpty()) {
                    if (hasData && currentEvent.length() > 0) {
                        // 注入事件 ID
                        String idLine = "id: " + seqNo + "\n";
                        response.getOutputStream().write(idLine.getBytes(StandardCharsets.UTF_8));
                        response.getOutputStream().write(currentEvent.toString().getBytes(StandardCharsets.UTF_8));
                        response.getOutputStream().write("\n".getBytes());
                        response.getOutputStream().flush();
                        
                        // ⭐ 缓存事件到 Redis（用于断线续传）
                        if (redisKey != null) {
                            String dataPart = extractDataPart(currentEvent.toString());
                            if (dataPart != null) {
                                try {
                                    redisTemplate.opsForHash().put(redisKey, String.valueOf(seqNo), dataPart);
                                    redisTemplate.expire(redisKey, SSE_BUFFER_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                                } catch (Exception e) {
                                    logger.debug("[StreamChat] 缓存 SSE 事件失败: seqNo={}", seqNo);
                                }
                            }
                        }
                        
                        seqNo++;
                    }
                    currentEvent = new StringBuilder();
                    hasData = false;
                } else {
                    currentEvent.append(line).append("\n");
                    if (line.startsWith("data:")) {
                        hasData = true;
                    }
                }
            }
            
            logger.info("[StreamChat] SSE 转发完成, 事件数={}", seqNo - (initialSeqNo != null ? initialSeqNo : 1));
            
        } catch (Exception e) {
            logger.error("[StreamChat] SSE 转发失败: {}", e.getMessage(), e);
            
            if (!response.isCommitted()) {
                try {
                    String errorEvent = String.format("event: error\ndata: {\"type\":\"error\",\"content\":\"%s\"}\n\n", 
                            escapeForSSE(e.getMessage()));
                    response.getOutputStream().write(errorEvent.getBytes(StandardCharsets.UTF_8));
                    response.getOutputStream().flush();
                } catch (Exception ex) {
                    logger.error("[StreamChat] 发送错误事件失败: {}", ex.getMessage());
                }
            }
            
        } finally {
            // 清理资源
            if (inputStream != null) {
                try { inputStream.close(); } catch (Exception e) {
                    log.debug("[StreamChat] 关闭 inputStream 异常: {}", e.getMessage());
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * ⭐ 从 Redis 读取多 Agent SSE 事件并转发给前端
     */
    private void forwardEventsFromRedis(HttpServletResponse response, String eventsKey) {
        try {
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");

            while (true) {
                String eventJson = redisTemplate.opsForList().leftPop(eventsKey);
                if (eventJson == null) break;

                // 解析 JSON 获取 type 和 agent
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> event = objectMapper.readValue(eventJson, Map.class);
                    String type = (String) event.getOrDefault("type", "");
                    Object agent = event.get("agent");

                    String sseLine;
                    if ("summarizing".equals(type)) {
                        sseLine = "event: response\ndata: " + eventJson + "\n\n";
                    } else if (agent != null) {
                        // agent 字段非空 → 标注来源
                        sseLine = "event: " + type + "\ndata: " + eventJson + "\n\n";
                    } else {
                        sseLine = "event: " + type + "\ndata: " + eventJson + "\n\n";
                    }
                    response.getOutputStream().write(sseLine.getBytes(StandardCharsets.UTF_8));
                    response.getOutputStream().flush();
                } catch (Exception e) {
                    logger.warn("[StreamChat] 解析 SSE 事件失败: {}", e.getMessage());
                }
            }

            // 发送 done 事件
            String done = "event: done\ndata: {\"type\":\"done\"}\n\n";
            response.getOutputStream().write(done.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();

        } catch (Exception e) {
            logger.error("[StreamChat] 多 Agent SSE 转发失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ URL 编码
     */
    private String encodeUrl(String str) {
        if (str == null) return "";
        try {
            return java.net.URLEncoder.encode(str, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return str;
        }
    }
    
    /**
     * ⭐ SSE 特殊字符转义
     */
    private String escapeForSSE(String str) {
        if (str == null) return "";
        return str
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
