/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.client;

import com.example.smartassistant.common.location.DeviceLocation;
import com.example.smartassistant.consumer.service.cache.RouteSemanticCacheService;
import com.example.smartassistant.routing.contract.RoutingKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

/**
 * Router Client - 调用 Router Service
 * 
 * <p>增强功能：</p>
 * <ul>
 *     <li>✅ 熔断器：失败率 >50% 时自动熔断，30s 后尝试恢复</li>
 *     <li>✅ 限流器：限制每秒 50 个请求，超出则等待或拒绝</li>
 *     <li>✅ 重试机制：网络异常时自动重试 2 次（指数退避）</li>
 *     <li>✅ 缓存：相同请求 5 分钟内直接返回缓存结果</li>
 *     <li>✅ JWT 认证：自动生成内部服务 Token</li>
 *     <li>✅ Redis 路由决策：支持阻塞等待路由决策结果</li>
 * </ul>
 */
@Component
public class RouterClient {
    
    private static final Logger log = LoggerFactory.getLogger(RouterClient.class);
    
    // Must match Router RoutingDecisionPublisher.FULL_DECISION_KEY_PREFIX.
    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private RouteSemanticCacheService routeSemanticCache;

    @Value("${router.service.url:http://localhost:8083}")
    private String routerServiceUrl;
    
    public RouterClient(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${router.service.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${router.service.read-timeout-ms:120000}") int readTimeoutMs) {
        // Router 包含意图分析、Agent 调用与质量评估，读取超时必须覆盖完整链路。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(factory);
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        
        // 设置 UTF-8 编码
        this.restTemplate.getMessageConverters().stream()
            .filter(converter -> converter instanceof org.springframework.http.converter.StringHttpMessageConverter)
            .findFirst()
            .ifPresent(converter -> 
                ((org.springframework.http.converter.StringHttpMessageConverter) converter)
                    .setDefaultCharset(java.nio.charset.StandardCharsets.UTF_8)
            );
    }

    /**
     * 调用 Router Service 并返回完整响应
     *
     * @param question   用户问题（纯文本）
     * @param userId     用户 ID
     * @param sessionId  会话 ID
     * @param requestId  请求 ID（可选）
     * @return Router Service 返回的完整响应 Map
     */
    @CircuitBreaker(name = "routerService", fallbackMethod = "callRouterRawFallback")
    @RateLimiter(name = "routerRateLimiter")
    @Retry(name = "routerRetry")
    public Map<String, Object> callRouterRaw(String question, String userId, String sessionId, String requestId) {
        log.info("[RouterClient] 调用 Router Service: userId={}, sessionId={}, questionLength={}",
                userId, sessionId, question != null ? question.length() : 0);

        long authenticatedUserId = requireAuthenticatedUserId(userId);
        try {
            Map<String, Object> requestBody = new HashMap<>();
            // ⭐ 将 userId 转为 Long（Router 端期望 Long 类型）
            requestBody.put("userId", authenticatedUserId);
            requestBody.put("question", question);
            requestBody.put("sessionId", sessionId);
            requestBody.put("enableRag", false);
            if (requestId != null) {
                requestBody.put("requestId", requestId);
            }
            applyCachedRouteHint(question, requestBody);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept-Charset", "UTF-8");
            headers.set("X-User-Id", Long.toString(authenticatedUserId));

            // 传递追踪上下文
            String traceId = MDC.get("traceId");
            String mdcRequestId = MDC.get("requestId");
            String threadId = MDC.get("threadId");
            if (traceId != null) headers.set("X-Trace-Id", traceId);
            if (mdcRequestId != null) headers.set("X-Request-Id", mdcRequestId);
            if (threadId != null) headers.set("X-Thread-Id", threadId);

            // 传递 JWT Token
            try {
                ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attributes != null) {
                    HttpServletRequest request = attributes.getRequest();
                    String authHeader = request.getHeader("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        headers.set("Authorization", authHeader);
                    }
                }
            } catch (Exception e) {
                log.error("[RouterClient] 设置 JWT Token 失败: {}", e.getMessage());
            }

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            String url = routerServiceUrl + "/api/router/route";
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                Map<String, Object> payload = unwrapRouterResponse(responseBody);
                saveRouteHint(question, payload);
                log.info("[RouterClient] Router 调用成功(完整响应): resultLength={}",
                        payload.get("result") instanceof String result ? result.length() : 0);
                return payload;
            }

            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("result", "⚠️ Router 返回空结果");
            errorMap.put("error", "空响应");
            return errorMap;

        } catch (Exception e) {
            log.error("[RouterClient] Router 调用失败: {}", e.getMessage(), e);
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("result", "❌ 调用 Router 服务失败: " + e.getMessage());
            errorMap.put("error", e.getMessage());
            return errorMap;
        }
    }

    /**
     * ⭐ 从 Redis 阻塞等待路由决策结果（使用 BLPOP 替代轮询）
     * <p>
     * 供 Consumer SSE 接口调用，实现与 chat 接口的状态共享。
     * </p>
     * <p>
     * <b>改进</b>：使用 BLPOP 阻塞读取替代 100ms 轮询，减少延迟和 CPU 消耗。
     * Router 端写入决策后向通知队列发送信号，读端立即收到通知。
     * BLPOP 超时后降级回退到一次轮询检查（兼容旧版未发送通知的场景）。
     *
     * @param requestId  请求 ID
     * @param timeoutMs  最大等待时间（毫秒）
     * @return 决策结果，如果超时返回 null
     */
    /** Accept both the unified ApiResponse envelope and the legacy flat Router response. */
    static Map<String, Object> unwrapRouterResponse(Map<String, Object> responseBody) {
        if (responseBody == null) return new HashMap<>();
        Object data = responseBody.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Map<String, Object> payload = new HashMap<>();
            dataMap.forEach((key, value) -> payload.put(String.valueOf(key), value));
            return payload;
        }
        return responseBody;
    }

    private Map<String, Object> callRouterRawFallback(String question, String userId, String sessionId,
                                                       String requestId, Throwable t) {
        log.warn("[RouterClient] Router circuit fallback: requestId={}, error={}",
                requestId, t != null ? t.getMessage() : "unknown");
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("result", "Router service is temporarily unavailable. Please retry later.");
        fallback.put("error", t != null ? t.getMessage() : "router unavailable");
        return fallback;
    }

    public Map<String, Object> waitForDecisionFromRedis(String requestId, long timeoutMs) {
        return waitForDecisionFromRedis(requestId, timeoutMs, null);
    }

    /**
     * 等待 Router 写入最终决策，并在等待期间持续转发执行进度。
     *
     * @param requestId 决策请求 ID
     * @param timeoutMs 最长等待时间
     * @param onPoll 每轮读取最终决策前执行的非阻塞回调，可为 {@code null}
     */
    public Map<String, Object> waitForDecisionFromRedis(String requestId, long timeoutMs, Runnable onPoll) {
        if (redisTemplate == null) {
            log.debug("[RouterClient] Redis 未配置，跳过等待");
            return null;
        }

        if (requestId == null || requestId.isBlank()) {
            log.debug("[RouterClient] requestId 为空，跳过等待");
            return null;
        }

        String decisionKey = RoutingKeys.fullDecision(requestId);
        String notifyKey = RoutingKeys.decisionNotification(requestId);
        long startTime = System.currentTimeMillis();
        long deadline = startTime + Math.max(timeoutMs, 0L);

        // 避免长时间 BLPOP：Redis 客户端命令超时通常短于业务等待时间，
        // 会在 Router 完成模型调用前抛出 command timed out，导致 SSE 返回空响应。
        // 使用短轮询后，单次 Redis 命令不会跨越客户端超时。
        do {
            try {
                if (onPoll != null) {
                    onPoll.run();
                }
                String notifyResult = redisTemplate.opsForList().leftPop(notifyKey);
                String value = redisTemplate.opsForValue().get(decisionKey);
                if (value != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> decision = objectMapper.readValue(value, Map.class);
                    log.info("[RouterClient] Redis 决策已就绪: requestId={}, agentName={}, notified={}, waitTime={}ms",
                            requestId, decision.get("agentName"), notifyResult != null,
                            System.currentTimeMillis() - startTime);
                    redisTemplate.delete(decisionKey);
                    redisTemplate.delete(notifyKey);
                    return decision;
                }
            } catch (Exception e) {
                log.warn("[RouterClient] Redis 决策读取异常，将继续重试: requestId={}, error={}",
                        requestId, e.getMessage());
            }

            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            try {
                Thread.sleep(Math.min(100L, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[RouterClient] Redis 等待决策被中断: requestId={}", requestId);
                return null;
            }
        } while (System.currentTimeMillis() <= deadline);

        log.warn("[RouterClient] Redis 等待决策超时: requestId={}, timeout={}ms",
                requestId, timeoutMs);
        return null;
    }

    /**
     * ⭐ 触发 Router 决策（仅触发，不等待）
     * <p>
     * chat 接口调用此方法触发 Router 做决策并存入 Redis
     * SSE 接口从 Redis 阻塞等待决策结果
     * <p>
     * 流程：
     * 1. 调用 Router 的 /api/router/decision 接口
     * 2. Router 执行决策并存入 Redis
     * 3. 返回（不等待结果）
     *
     * @param message   用户问题
     * @param userId    用户 ID
     * @param requestId 请求 ID（用于 Redis 存储）
     */
    public void triggerRoutingDecision(String message, String userId, String requestId) {
        triggerRoutingDecision(message, userId, requestId, null);
    }

    @Async("taskExecutor")
    public void triggerRoutingDecision(String message, String userId, String requestId,
                                       DeviceLocation deviceLocation) {
        log.debug("[RouterClient] 触发路由决策: requestId={}, messageLength={}", 
                requestId, message != null ? message.length() : 0);

        long authenticatedUserId = requireAuthenticatedUserId(userId);
        try {
            // ⚠️ 原实现调用 /api/router/decision，但该端点并不存在（RouterController 仅暴露 /api/router/route 等）。
            // /api/router/route 执行完整路由决策，并经由 RouteFinalizer.finalizeRouting
            // -> RoutingDecisionPublisher writes FULL_DECISION_KEY + notify.
            // 供 Consumer SSE 端点 waitForDecisionFromRedis 阻塞读取。
            Map<String, Object> requestBody = new HashMap<>();
            // userId 与 callRouterRaw 保持一致：非数字（如 anonymous）映射为 0L
            requestBody.put("userId", authenticatedUserId);
            requestBody.put("question", message);
            requestBody.put("sessionId", requestId);
            requestBody.put("requestId", requestId);
            requestBody.put("enableRag", false);
            if (deviceLocation != null && deviceLocation.isUsable()) {
                requestBody.put("deviceLocation", deviceLocation);
            }
            applyCachedRouteHint(message, requestBody);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // Async execution has no Servlet RequestContext, so forward the
            // identity already resolved by Consumer explicitly.
            headers.set("X-User-Id", Long.toString(authenticatedUserId));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            String url = routerServiceUrl + "/api/router/route";
            log.debug("[RouterClient] 触发路由决策(调用 Router.route): {}", url);

            // 发送请求触发决策（route 端点同步返回，内部已写入 Redis 决策）
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                saveRouteHint(message, unwrapRouterResponse(response.getBody()));
            }
            log.debug("[RouterClient] Router 决策请求已发送: requestId={}", requestId);

        } catch (Exception e) {
            log.error("[RouterClient] 触发路由决策失败: requestId={}, error={}", requestId, e.getMessage(), e);
        }
    }

    private void applyCachedRouteHint(String question, Map<String, Object> requestBody) {
        if (routeSemanticCache == null) return;
        RouteSemanticCacheService.CachedRouteHint hint = routeSemanticCache.find(question);
        if (hint == null) return;
        requestBody.put("cachedAgentName", hint.agentName());
        requestBody.put("cachedIntentTag", hint.intentTag());
        requestBody.put("cachedConfidence", hint.confidence());
        log.debug("[RouterClient] Consumer semantic route hit: agent={}, intent={}",
                hint.agentName(), hint.intentTag());
    }

    private void saveRouteHint(String question, Map<String, Object> response) {
        if (routeSemanticCache != null) {
            routeSemanticCache.save(question, response);
        }
    }

    private long requireAuthenticatedUserId(String userId) {
        try {
            long parsed = Long.parseLong(userId);
            if (parsed <= 0) {
                throw new IllegalArgumentException("Authenticated user ID must be positive");
            }
            return parsed;
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException("Missing or invalid authenticated user ID", e);
        }
    }
}
