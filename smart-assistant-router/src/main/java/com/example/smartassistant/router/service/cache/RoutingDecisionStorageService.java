/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * ⭐ 路由决策存储服务
 * <p>
 * 使用 Redis 存储路由决策，支持：
 * - 请求 ID 生成
 * - 决策结果存储（带过期时间）
 * - 阻塞等待决策结果
 */
@Service
@Slf4j
public class RoutingDecisionStorageService {

    private static final String ROUTING_DECISION_KEY_PREFIX = "routing:decision:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RoutingDecisionStorageService(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * ⭐ 生成请求 ID（用于 Redis key）
     */
    public String generateRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * ⭐ 存储路由决策结果
     * <p>
     * 保存后同时向通知队列发送 BLPOP 信号，使等待端能立即收到通知。
     *
     * @param requestId 请求 ID
     * @param decision  决策结果
     */
    public void saveDecision(String requestId, Map<String, Object> decision) {
        if (redisTemplate == null) {
            log.warn("[RoutingDecisionStorage] Redis 未配置，跳过存储");
            return;
        }

        try {
            String key = ROUTING_DECISION_KEY_PREFIX + requestId;
            String value = objectMapper.writeValueAsString(decision);
            redisTemplate.opsForValue().set(key, value, DEFAULT_TTL.getSeconds(), TimeUnit.SECONDS);

            // ⭐ 向 BLPOP 通知队列发送信号，唤醒等待端
            String notifyKey = ROUTING_DECISION_KEY_PREFIX + "notify:" + requestId;
            redisTemplate.opsForList().rightPush(notifyKey, requestId);
            redisTemplate.expire(notifyKey, DEFAULT_TTL.getSeconds(), TimeUnit.SECONDS);

            log.debug("[RoutingDecisionStorage] 决策已存储并通知: requestId={}, agentName={}",
                    requestId, decision.get("agentName"));
        } catch (JsonProcessingException e) {
            log.error("[RoutingDecisionStorage] 序列化决策失败: requestId={}", requestId, e);
        }
    }

    /**
     * ⭐ 获取路由决策结果
     *
     * @param requestId 请求 ID
     * @return 决策结果，如果不存在返回 null
     */
    public Map<String, Object> getDecision(String requestId) {
        if (redisTemplate == null) {
            log.warn("[RoutingDecisionStorage] Redis 未配置，无法获取决策");
            return null;
        }

        try {
            String key = ROUTING_DECISION_KEY_PREFIX + requestId;
            String value = redisTemplate.opsForValue().get(key);

            if (value == null) {
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> decision = objectMapper.readValue(value, Map.class);
            log.debug("[RoutingDecisionStorage] 决策已获取: requestId={}, agentName={}",
                    requestId, decision.get("agentName"));
            return decision;
        } catch (JsonProcessingException e) {
            log.error("[RoutingDecisionStorage] 反序列化决策失败: requestId={}", requestId, e);
            return null;
        }
    }

    /**
     * ⭐ 阻塞等待路由决策结果（支持 BLPOP 通知机制）
     * <p>
     * 使用 Redis List 的 BLPOP 替代轮询，减少延迟和 CPU 消耗。
     * 写入决策时同时 push 到通知队列，读端阻塞等待。
     *
     * @param requestId  请求 ID
     * @param maxWaitMs  最大等待时间（毫秒）
     * @param pollIntervalMs 轮询间隔（毫秒，仅作为 BLPOP 超时后的降级回退）
     * @return 决策结果，如果超时返回 null
     */
    public Map<String, Object> waitForDecision(String requestId, long maxWaitMs, long pollIntervalMs) {
        if (redisTemplate == null) {
            log.warn("[RoutingDecisionStorage] Redis 未配置，无法等待决策");
            return null;
        }

        String notifyKey = ROUTING_DECISION_KEY_PREFIX + "notify:" + requestId;
        long startTime = System.currentTimeMillis();

        try {
            // ⭐ 使用 BLPOP 阻塞等待通知（最大等待 maxWaitMs 毫秒）
            // 如果 BLPOP 成功返回，说明决策已就绪；否则超时
            String notifyResult = redisTemplate.opsForList().leftPop(
                    notifyKey, Duration.ofMillis(maxWaitMs));

            if (notifyResult != null) {
                log.debug("[RoutingDecisionStorage] BLPOP 收到决策通知: requestId={}, waitTime={}ms",
                        requestId, System.currentTimeMillis() - startTime);
                return getDecision(requestId);
            }

            // BLPOP 超时，回退到轮询检查（兼容旧版未发送通知的场景）
            log.debug("[RoutingDecisionStorage] BLPOP 超时，回退到轮询: requestId={}", requestId);
            return pollForDecision(requestId, maxWaitMs - (System.currentTimeMillis() - startTime), pollIntervalMs);

        } catch (Exception e) {
            log.warn("[RoutingDecisionStorage] BLPOP 等待异常: requestId={}", requestId, e);
            // 异常降级到轮询
            return pollForDecision(requestId, maxWaitMs - (System.currentTimeMillis() - startTime), pollIntervalMs);
        }
    }

    /**
     * 降级轮询方式获取决策（兼容旧版）
     */
    private Map<String, Object> pollForDecision(String requestId, long remainingMs, long pollIntervalMs) {
        if (remainingMs <= 0) return null;

        long startTime = System.currentTimeMillis();
        int attempts = 0;

        while (System.currentTimeMillis() - startTime < remainingMs) {
            attempts++;
            Map<String, Object> decision = getDecision(requestId);
            if (decision != null) {
                log.debug("[RoutingDecisionStorage] 轮询到决策: requestId={}, attempts={}, waitTime={}ms",
                        requestId, attempts, System.currentTimeMillis() - startTime);
                return decision;
            }
            try {
                Thread.sleep(Math.min(pollIntervalMs, remainingMs - (System.currentTimeMillis() - startTime)));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        log.warn("[RoutingDecisionStorage] 轮询超时: requestId={}, attempts={}", requestId, attempts);
        return null;
    }

    /**
     * ⭐ 删除决策结果
     *
     * @param requestId 请求 ID
     */
    public void deleteDecision(String requestId) {
        if (redisTemplate == null) {
            return;
        }

        String key = ROUTING_DECISION_KEY_PREFIX + requestId;
        redisTemplate.delete(key);
        log.debug("[RoutingDecisionStorage] 决策已删除: requestId={}", requestId);
    }

    /**
     * ⭐ 检查决策是否存在
     *
     * @param requestId 请求 ID
     * @return true 如果存在
     */
    public boolean hasDecision(String requestId) {
        if (redisTemplate == null) {
            return false;
        }

        String key = ROUTING_DECISION_KEY_PREFIX + requestId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
