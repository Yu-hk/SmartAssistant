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
            log.debug("[RoutingDecisionStorage] 决策已存储: requestId={}, agentName={}",
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
     * ⭐ 阻塞等待路由决策结果
     * <p>
     * 轮询 Redis 直到获取到决策结果或超时
     *
     * @param requestId  请求 ID
     * @param maxWaitMs  最大等待时间（毫秒）
     * @param pollIntervalMs 轮询间隔（毫秒）
     * @return 决策结果，如果超时返回 null
     */
    public Map<String, Object> waitForDecision(String requestId, long maxWaitMs, long pollIntervalMs) {
        if (redisTemplate == null) {
            log.warn("[RoutingDecisionStorage] Redis 未配置，无法等待决策");
            return null;
        }

        long startTime = System.currentTimeMillis();
        int attempts = 0;

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            attempts++;
            Map<String, Object> decision = getDecision(requestId);

            if (decision != null) {
                log.debug("[RoutingDecisionStorage] 等待到决策: requestId={}, attempts={}, waitTime={}ms",
                        requestId, attempts, System.currentTimeMillis() - startTime);
                return decision;
            }

            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[RoutingDecisionStorage] 等待被中断: requestId={}", requestId);
                return null;
            }
        }

        log.warn("[RoutingDecisionStorage] 等待决策超时: requestId={}, maxWaitMs={}, attempts={}",
                requestId, maxWaitMs, attempts);
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
