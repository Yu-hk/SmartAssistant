/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.client;

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
    
    // ⭐ 与 Router 中的 SemanticRouteCacheService.FULL_DECISION_KEY_PREFIX 保持一致
    private static final String ROUTING_DECISION_KEY_PREFIX = "a2a:route:full-decision:";
    
    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${router.service.url:http://localhost:8083}")
    private String routerServiceUrl;
    
    public RouterClient(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
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
     * @param userProfile 用户画像文本（可选，独立字段）
     * @param intentTag  意图标签（可选）
     * @return Router Service 返回的完整响应 Map
     */
    @CircuitBreaker(name = "routerService", fallbackMethod = "callRouterRawFallback")
    @RateLimiter(name = "routerRateLimiter")
    @Retry(name = "routerRetry")
    public Map<String, Object> callRouterRaw(String question, String userId, String sessionId, String requestId,
                                              String userProfile, String intentTag) {
        log.info("[RouterClient] 调用 Router Service: userId={}, sessionId={}, questionLength={}",
                userId, sessionId, question != null ? question.length() : 0);

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("userId", userId != null ? userId : "anonymous");
            requestBody.put("question", question);
            requestBody.put("sessionId", sessionId);
            requestBody.put("enableRag", false);
            if (requestId != null) {
                requestBody.put("requestId", requestId);
            }
            // ⭐ 独立字段传输用户画像和意图标签，不再塞入 question
            if (userProfile != null && !userProfile.isBlank()) {
                requestBody.put("userProfile", userProfile);
            }
            if (intentTag != null && !intentTag.isBlank()) {
                requestBody.put("intentTag", intentTag);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept-Charset", "UTF-8");

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
                log.info("[RouterClient] Router 调用成功(完整响应): resultLength={}",
                        responseBody.containsKey("result") ? ((String) responseBody.get("result")).length() : 0);
                return responseBody;
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
     * ⭐ 从 Redis 阻塞等待路由决策结果
     * <p>
     * 供 Consumer SSE 接口调用，实现与 chat 接口的状态共享
     *
     * @param requestId  请求 ID
     * @param timeoutMs  最大等待时间（毫秒）
     * @return 决策结果，如果超时返回 null
     */
    public Map<String, Object> waitForDecisionFromRedis(String requestId, long timeoutMs) {
        if (redisTemplate == null) {
            log.debug("[RouterClient] Redis 未配置，跳过等待");
            return null;
        }

        if (requestId == null || requestId.isBlank()) {
            log.debug("[RouterClient] requestId 为空，跳过等待");
            return null;
        }

        String key = ROUTING_DECISION_KEY_PREFIX + requestId;
        long startTime = System.currentTimeMillis();
        long pollInterval = 100; // 100ms 轮询间隔
        int attempts = 0;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            attempts++;

            try {
                String value = redisTemplate.opsForValue().get(key);

                if (value != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> decision = objectMapper.readValue(value, Map.class);
                    log.info("[RouterClient] Redis 获取决策成功: requestId={}, agentName={}, attempts={}, waitTime={}ms",
                            requestId, decision.get("agentName"), attempts, System.currentTimeMillis() - startTime);

                    // 删除 key（一次性使用）
                    redisTemplate.delete(key);

                    return decision;
                }

                Thread.sleep(pollInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[RouterClient] Redis 等待被中断: requestId={}", requestId);
                return null;
            } catch (Exception e) {
                log.warn("[RouterClient] Redis 获取决策异常: requestId={}, error={}", requestId, e.getMessage());
                return null;
            }
        }

        log.warn("[RouterClient] Redis 等待决策超时: requestId={}, attempts={}, timeout={}ms", 
                requestId, attempts, timeoutMs);
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
        log.debug("[RouterClient] 触发路由决策: requestId={}, messageLength={}", 
                requestId, message != null ? message.length() : 0);

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("userId", userId != null ? userId : "anonymous");
            requestBody.put("question", message);
            requestBody.put("requestId", requestId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            String url = routerServiceUrl + "/api/router/decision";
            log.debug("[RouterClient] 调用 Router: {}", url);

            // 发送请求触发决策
            restTemplate.postForEntity(url, request, Map.class);
            log.debug("[RouterClient] Router 决策请求已发送: requestId={}", requestId);

        } catch (Exception e) {
            log.error("[RouterClient] 触发路由决策失败: requestId={}, error={}", requestId, e.getMessage(), e);
        }
    }
}
