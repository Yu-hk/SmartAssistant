/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.common.sse.SseEvent;
import com.example.smartassistant.common.sse.SseEventBus;
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

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamChat(
            @RequestParam String message,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false, defaultValue = "true") boolean showThinking,
            @RequestParam(required = false, defaultValue = "5") int priority,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response) {

        logger.info("[StreamChat] 流式请求: messageLength={}, requestId={}, priority={}",
                message != null ? message.length() : 0, requestId, priority);

        SseEventBus bus = createBus(response, requestId);

        // 断线续传
        if (requestId != null && lastEventId != null && !lastEventId.isBlank()) {
            try {
                if (bus.resume(Long.parseLong(lastEventId))) return;
            } catch (NumberFormatException ignored) {}
        }

        // 获取路由决策
        Map<String, Object> decision = getRoutingDecision(requestId, bus);
        if (decision == null || !decision.containsKey("agentName")) {
            bus.sendError("路由决策获取失败，请稍后重试");
            return;
        }

        String agentName = (String) decision.get("agentName");
        Double confidence = (Double) decision.getOrDefault("confidence", 0.0);
        logger.info("[StreamChat] 路由: agentName={}, confidence={}", agentName, confidence);
        bus.sendRouted(agentName, confidence);

        // 多 Agent SSE 事件检查
        if (requestId != null && redisTemplate != null) {
            String eventsKey = "routing:sse:events:" + requestId;
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
        if (requestId != null && !requestId.isBlank()) {
            if (!handleQueue(requestId, priority, bus)) return;
        }

        // 转发 Agent SSE
        String agentUrl = agentStreamClient.getStreamUrl(agentName)
                + "?message=" + encodeUrl(message) + "&showThinking=" + showThinking;
        try {
            forwardAgentStream(bus, agentUrl);
        } finally {
            if (requestId != null && !requestId.isBlank()) {
                requestQueueService.complete(requestId);
            }
        }
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamChatPost(@RequestBody Map<String, Object> request, HttpServletResponse response) {
        String message = (String) request.getOrDefault("message", request.get("question"));
        String requestId = (String) request.getOrDefault("requestId", null);
        boolean showThinking = request.containsKey("showThinking")
                ? (Boolean) request.get("showThinking") : true;
        int priority = request.containsKey("priority")
                ? ((Number) request.get("priority")).intValue() : RequestQueueService.PRIORITY_NORMAL;
        streamChat(message, requestId, showThinking, priority, null, response);
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

    private Map<String, Object> getRoutingDecision(String requestId, SseEventBus bus) {
        if (requestId == null || requestId.isBlank()) return null;
        bus.sendWaiting();
        try {
            return routerClient.waitForDecisionFromRedis(requestId, DECISION_TIMEOUT_MS);
        } catch (Exception e) {
            logger.error("[StreamChat] 获取决策失败: {}", e.getMessage());
            return null;
        }
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
            } catch (Exception ignored) {}
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
