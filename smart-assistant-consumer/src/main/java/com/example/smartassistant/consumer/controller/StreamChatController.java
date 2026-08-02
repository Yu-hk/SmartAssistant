/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.common.audit.TokenUsageCache;
import com.example.smartassistant.common.sse.SseEvent;
import com.example.smartassistant.common.sse.SseEventBus;
import com.example.smartassistant.consumer.client.AgentStreamClient;
import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.service.core.RequestQueueService;
import com.example.smartassistant.consumer.service.infrastructure.RoutingCallLogService;
import com.example.smartassistant.consumer.service.session.CustomerSessionContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Consumer 统一流式对话控制器。
 *
 * <p>使用 {@link SseEventBus} 替代内联 SSE 构建逻辑。</p>
 */
@RestController
@RequestMapping("/api/math/stream")
@Slf4j
public class StreamChatController {

    private static final Logger logger = LoggerFactory.getLogger(StreamChatController.class);

    private static final long DECISION_TIMEOUT_MS = 60000;

    private final RouterClient routerClient;
    private final AgentStreamClient agentStreamClient;
    private final StringRedisTemplate redisTemplate;
    private final RequestQueueService requestQueueService;
    private final RoutingCallLogService routingCallLogService;
    private final CustomerSessionContextService sessionContextService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StreamChatController(
            RouterClient routerClient,
            AgentStreamClient agentStreamClient,
            RequestQueueService requestQueueService,
            RoutingCallLogService routingCallLogService,
            CustomerSessionContextService sessionContextService,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.routerClient = routerClient;
        this.agentStreamClient = agentStreamClient;
        this.requestQueueService = requestQueueService;
        this.routingCallLogService = routingCallLogService;
        this.sessionContextService = sessionContextService;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamChat(
            @RequestParam String message,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false, defaultValue = "true") boolean showThinking,
            @RequestParam(required = false, defaultValue = "5") int priority,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response) {

        logger.info("[StreamChat] 流式请求: messageLength={}, requestId={}, priority={}",
                message != null ? message.length() : 0, requestId, priority);

        // sessionId 表示连续会话；decisionKey 必须表示单次请求。两者复用会导致多轮对话
        // 共享 Redis 决策/通知键，从而读到上一轮结果或互相阻塞。
        String decisionKey = resolveDecisionKey(requestId, sessionId);
        long routingStartedAt = System.currentTimeMillis();

        SseEventBus bus = createBus(response, decisionKey);

        // 断线续传
        if (requestId != null && lastEventId != null && !lastEventId.isBlank()) {
            try {
                if (bus.resume(Long.parseLong(lastEventId))) return;
            } catch (NumberFormatException ignored) {}
        }

        // 会话业务实体按“认证用户 + sessionId”隔离，仅为明确的订单追问补全上轮订单号。
        String userId = resolveUserId();
        String routedMessage = sessionContextService.enrichOrderReference(userId, sessionId, message);

        // 获取路由决策
        Map<String, Object> decision = getRoutingDecision(
                decisionKey, sessionId, routedMessage, userId, bus);
        if (decision == null || !decision.containsKey("agentName")) {
            routingCallLogService.saveLog(
                    decisionKey, sessionId, message, null, "ROUTER",
                    null, null, System.currentTimeMillis() - routingStartedAt,
                    "FAILED", "路由决策获取失败或缺少 agentName");
            bus.sendError("路由决策获取失败，请稍后重试");
            return;
        }

        String agentName = (String) decision.get("agentName");
        Double confidence = numberAsDouble(decision.get("confidence"));
        String routeMethod = String.valueOf(decision.getOrDefault("routingMethod", "ROUTER"));
        Object routedResultForAudit = decision.get("result");
        routingCallLogService.saveLog(
                decisionKey, sessionId, message, agentName, routeMethod,
                confidence,
                routedResultForAudit != null ? String.valueOf(routedResultForAudit) : null,
                System.currentTimeMillis() - routingStartedAt,
                "SUCCESS", null);
        logger.info("[StreamChat] 路由: agentName={}, confidence={}", agentName, confidence);
        // 对齐前端 useChat 期望的 init 事件（带 sessionId/intent），保证消息块正确挂载
        try {
            String initJson = objectMapper.writeValueAsString(Map.of(
                    "type", "init",
                    "sessionId", sessionId != null ? sessionId : "",
                    "intent", decision.getOrDefault("intentTag", "unknown")));
            bus.send(SseEvent.raw("init", initJson));
        } catch (Exception ignored) {
        }
        bus.sendRouted(agentName, confidence);

        // Router 的 /route 接口会同步完成 Agent 调用，并把完整结果写入决策。
        // 前端 EventSource 只传 sessionId 时，直接转为标准 text/done 事件。
        Object routedResult = decision.get("result");
        if (routedResult instanceof String result && !result.isBlank()) {
            try {
                sessionContextService.rememberConversationTurn(
                        userId, sessionId, message, result);
                sessionContextService.rememberOrderCandidates(userId, sessionId, result);
                String textJson = objectMapper.writeValueAsString(Map.of(
                        "type", "text",
                        "content", result));
                bus.send(SseEvent.raw("text", textJson));
                bus.sendDone();
                injectTokenUsageEvent(bus, decisionKey);
                logger.info("[StreamChat] Router 完整结果已转发: requestId={}, resultLength={}",
                        decisionKey, result.length());
            } catch (Exception e) {
                logger.error("[StreamChat] Router 结果转发失败: requestId={}, error={}",
                        decisionKey, e.getMessage());
                bus.sendError("Agent 结果转发失败");
            }
            return;
        }

        // 多 Agent SSE 事件检查
        if (decisionKey != null && redisTemplate != null) {
            String eventsKey = "routing:sse:events:" + decisionKey;
            Long eventCount = redisTemplate.opsForList().size(eventsKey);
            if (eventCount != null && eventCount > 0) {
                logger.info("[StreamChat] 多 Agent SSE: {} 条", eventCount);
                forwardRedisEvents(bus, eventsKey);
                return;
            }
        }

        // 流式支持检查
        if (!agentStreamClient.isStreamingSupported(agentName)) {
            bus.sendError("Agent 不支持流式响应");
            return;
        }

        // 排队
        if (decisionKey != null && !decisionKey.isBlank()) {
            if (!handleQueue(decisionKey, priority, bus)) return;
        }

        // 转发 Agent SSE
        String agentUrl = agentStreamClient.getStreamUrl(agentName)
                + "?message=" + encodeUrl(routedMessage) + "&showThinking=" + showThinking;
        try {
            forwardAgentStream(bus, agentUrl);
            // ⭐ 流结束后发送 token 用量事件
            injectTokenUsageEvent(bus, decisionKey);
        } finally {
            if (decisionKey != null && !decisionKey.isBlank()) {
                requestQueueService.complete(decisionKey);
            }
        }
    }

    /**
     * ⭐ 从 {@link TokenUsageCache} 提取 token 用量并以 SSE 事件发送。
     */
    private void injectTokenUsageEvent(SseEventBus bus, String requestId) {
        var tokenUsage = TokenUsageCache.consume(requestId);
        if (tokenUsage == null) return;
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "token_usage",
                    "promptTokens", tokenUsage.promptTokens(),
                    "completionTokens", tokenUsage.completionTokens(),
                    "totalTokens", tokenUsage.totalTokens()
            ));
            bus.send(SseEvent.raw("token_usage", json));
            log.info("[StreamChat] Token 用量已回传: {}", json);
        } catch (Exception e) {
            log.warn("[StreamChat] Token 用量回传失败: {}", e.getMessage());
        }
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamChatPost(@RequestBody Map<String, Object> request, HttpServletResponse response) {
        String message = (String) request.getOrDefault("message", request.get("question"));
        String requestId = (String) request.getOrDefault("requestId", null);
        String sessionId = (String) request.getOrDefault("sessionId", null);
        boolean showThinking = request.containsKey("showThinking")
                ? (Boolean) request.get("showThinking") : true;
        int priority = request.containsKey("priority")
                ? ((Number) request.get("priority")).intValue() : RequestQueueService.PRIORITY_NORMAL;
        streamChat(message, requestId, sessionId, showThinking, priority, null, response);
    }

    @PostMapping("/chat/cancel")
    public void cancelChat(@RequestBody Map<String, String> request) {
        String requestId = request.get("requestId");
        if (requestId != null && !requestId.isBlank()) {
            requestQueueService.complete(requestId);
            logger.info("[StreamChat] 用户取消: requestId={}", requestId);
        }
    }

    // ==================== 内部方法 ====================

    private SseEventBus createBus(HttpServletResponse response, String requestId) {
        SseEventBus.RedisZSetCache redisCache = redisTemplate != null
                ? new RedisZSetCacheAdapter(redisTemplate) : null;
        return new SseEventBus(response, requestId, redisCache);
    }

    private Map<String, Object> getRoutingDecision(String decisionKey, String sessionId,
                                                   String message, String userId,
                                                   SseEventBus bus) {
        // ⚠️ 先触发路由决策写入 Redis（修复原"只等待、不触发"导致永久失败的问题）
        try {
            routerClient.triggerRoutingDecision(message, userId, sessionId, decisionKey);
        } catch (Exception e) {
            logger.warn("[StreamChat] 触发路由决策异常(将尝试等待已有决策): {}", e.getMessage());
        }
        bus.sendWaiting();
        try {
            return routerClient.waitForDecisionFromRedis(decisionKey, DECISION_TIMEOUT_MS);
        } catch (Exception e) {
            logger.error("[StreamChat] 获取决策失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析用户 ID：从网关注入的 X-User-Id 取；SSE 走白名单(免鉴权)时为 anonymous。
     */
    private String resolveUserId() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                String uid = attrs.getRequest().getHeader("X-User-Id");
                if (uid != null && !uid.isBlank()) return uid;
            }
        } catch (Exception ignored) {
        }
        return "anonymous";
    }

    private boolean handleQueue(String requestId, int priority, SseEventBus bus) {
        var slotResult = requestQueueService.tryAcquireWithQueue(requestId, null, priority);
        switch (slotResult) {
            case QUEUE_FULL:
                bus.sendError("系统繁忙，请稍后再试");
                return false;
            case QUEUED:
                int pos = requestQueueService.getQueuePosition(requestId);
                bus.sendQueue(pos, pos * 5000L);
                if (!requestQueueService.waitForSlot(requestId)) {
                    bus.sendTimeout("排队超时，请稍后重试");
                    return false;
                }
                bus.sendProcessing();
                return true;
            case ACQUIRED:
                bus.sendProcessing();
                return true;
            default:
                return true;
        }
    }

    private void forwardAgentStream(SseEventBus bus, String agentUrl) {
        try {
            URI uri = new URI(agentUrl);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(3600000);

            // 传递 Auth Token
            try {
                var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attrs != null) {
                    String auth = attrs.getRequest().getHeader("Authorization");
                    if (auth != null) conn.setRequestProperty("Authorization", auth);
                }
            } catch (Exception e) {
                logger.warn("[StreamChat] 设置 Authorization 失败: {}", e.getMessage());
            }
            conn.connect();
            bus.forwardStream(conn);
        } catch (Exception e) {
            logger.error("[StreamChat] 转发失败: {}", e.getMessage());
            bus.sendError(e.getMessage());
        }
    }

    private void forwardRedisEvents(SseEventBus bus, String eventsKey) {
        try {
            while (true) {
                String json = redisTemplate.opsForList().leftPop(eventsKey);
                if (json == null) break;
                bus.send(SseEvent.raw(extractType(json), json));
            }
            bus.sendDone();
        } catch (Exception e) {
            logger.error("[StreamChat] Redis 事件转发失败: {}", e.getMessage());
        }
    }

    private String extractType(String json) {
        try {
            Map<String, Object> event = objectMapper.readValue(json, Map.class);
            return (String) event.getOrDefault("type", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String encodeUrl(String str) {
        if (str == null) return "";
        try { return java.net.URLEncoder.encode(str, StandardCharsets.UTF_8); }
        catch (Exception e) { return str; }
    }

    static String resolveDecisionKey(String requestId, String sessionId) {
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        String prefix = sessionId != null && !sessionId.isBlank() ? sessionId : "sse";
        return prefix + "-" + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private static Double numberAsDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    /** Redis ZSet 适配器 */
    private record RedisZSetCacheAdapter(StringRedisTemplate template) implements SseEventBus.RedisZSetCache {
        @Override
        public void add(String key, String value, double score) {
            template.opsForZSet().add(key, value, score);
        }
        @Override
        public Set<String> rangeByScore(String key, long min, long max) {
            return template.opsForZSet().rangeByScore(key, min, max);
        }
        @Override
        public void expire(String key, long timeout, TimeUnit unit) {
            template.expire(key, timeout, unit);
        }
    }
}
