/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.common.api.AgentApiResponses;
import com.example.smartassistant.common.api.AgentStreamEvent;
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
            jakarta.servlet.http.HttpServletResponse response) {
        
        logger.info("[StreamChat] 收到流式请求: messageLength={}, requestId={}, showThinking={}", 
                message != null ? message.length() : 0, requestId, showThinking);

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
            forwardSSE(response, fullUrl);
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
        
        streamChat(message, requestId, showThinking, httpResponse);
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
    
    // ==================== SSE 事件发送统一方法 ====================

    /**
     * 使用 {@link AgentStreamEvent} 构建并发送 SSE 事件。
     *
     * @param response   HTTP 响应
     * @param eventName  SSE {@code event:} 字段值（如 "agent_waiting"）
     * @param event      AgentStreamEvent 对象，将被序列化为 JSON 作为 {@code data:} 字段
     */
    private void sendSseEvent(HttpServletResponse response, String eventName, AgentStreamEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            String sse = "event: " + eventName + "\ndata: " + json + "\n\n";
            response.getOutputStream().write(sse.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
        } catch (Exception e) {
            logger.debug("[StreamChat] 发送 SSE 事件失败: eventName={}, error={}", eventName, e.getMessage());
        }
    }

    /**
     * 发送等待事件给前端（使用 {@link AgentStreamEvent#TYPE_WAITING}）。
     */
    private void sendWaitingEvent(HttpServletResponse response) {
        sendSseEvent(response, AgentStreamEvent.TYPE_WAITING,
                AgentStreamEvent.waiting("正在分析意图..."));
    }

    /**
     * 发送错误事件给前端（使用 {@link AgentStreamEvent#TYPE_ERROR}）。
     */
    private void sendErrorEvent(HttpServletResponse response, String errorMessage) {
        if (response.isCommitted()) {
            logger.debug("[StreamChat] 响应已提交，跳过错误事件: {}", errorMessage);
            return;
        }
        sendSseEvent(response, AgentStreamEvent.TYPE_ERROR,
                AgentStreamEvent.error(errorMessage, AgentApiResponses.ERROR_INTERNAL, null));
    }

    // ==================== 排队相关 SSE 事件 ====================

    /**
     * 发送排队事件（基础设施事件，直接写 JSON 不用 AgentStreamEvent）。
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
     * 发送排队位置更新事件（基础设施事件）。
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
     * 发送处理中事件（使用 {@link AgentStreamEvent#TYPE_PROCESSING}）。
     */
    private void sendProcessingEvent(HttpServletResponse response) {
        sendSseEvent(response, AgentStreamEvent.TYPE_PROCESSING,
                AgentStreamEvent.processing());
        logger.info("[StreamChat] ▶️ 处理中事件");
    }

    /**
     * 发送超时事件（基础设施事件）。
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
     * ⭐ 转发 SSE 事件流
     */
    private void forwardSSE(jakarta.servlet.http.HttpServletResponse response, String agentUrl) {
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
            
            // 转发 SSE 事件
            inputStream = connection.getInputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                response.getOutputStream().write(buffer, 0, bytesRead);
                response.getOutputStream().flush();
            }
            
            logger.info("[StreamChat] SSE 转发完成");
            
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
                    // summarising 是遗留 Agent 使用的 type → 映射到完整回复事件
                    if ("summarizing".equals(type)) {
                        sseLine = "event: " + AgentStreamEvent.TYPE_COMPLETE + "\ndata: " + eventJson + "\n\n";
                    } else if (agent != null) {
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

            // 发送 done 事件（流结束基础设施事件）
            String doneLine = "event: done\ndata: {\"type\":\"done\"}\n\n";
            response.getOutputStream().write(doneLine.getBytes(StandardCharsets.UTF_8));
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
