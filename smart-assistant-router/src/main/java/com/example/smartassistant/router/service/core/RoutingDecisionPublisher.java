package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.audit.TokenUsageCache;
import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.routing.contract.RoutingKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Publishes one routing result for the Consumer SSE coordinator. */
@Service
public class RoutingDecisionPublisher {

    public static final String FULL_DECISION_KEY_PREFIX = RoutingKeys.FULL_DECISION_PREFIX;
    private static final Logger log = LoggerFactory.getLogger(RoutingDecisionPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RoutingDecisionPublisher(ObjectProvider<StringRedisTemplate> redisProvider, ObjectMapper objectMapper) {
        this.redisTemplate = redisProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    public void publish(String requestId, String agentName, double confidence,
                        String result, String intentTag,
                        TokenUsageCache.TokenUsage tokenUsage,
                        ToolUsageCache.ToolUsage toolUsage) {
        if (redisTemplate == null || requestId == null || requestId.isBlank()) return;
        try {
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("requestId", requestId);
            decision.put("agentName", agentName);
            decision.put("confidence", confidence);
            decision.put("result", result);
            decision.put("intentTag", intentTag);
            decision.put("timestamp", System.currentTimeMillis());
            if (tokenUsage != null) {
                if (tokenUsage.promptTokens() != null) decision.put("promptTokens", tokenUsage.promptTokens());
                if (tokenUsage.completionTokens() != null) decision.put("completionTokens", tokenUsage.completionTokens());
                if (tokenUsage.totalTokens() != null) decision.put("totalTokens", tokenUsage.totalTokens());
            }
            if (toolUsage != null) {
                decision.put("toolUsageComplete", toolUsage.complete());
                decision.put("toolCalls", toolUsage.calls());
            }
            String key = RoutingKeys.fullDecision(requestId);
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(decision), Duration.ofSeconds(120));
            String notifyKey = RoutingKeys.decisionNotification(requestId);
            redisTemplate.opsForList().rightPush(notifyKey, requestId);
            redisTemplate.expire(notifyKey, Duration.ofSeconds(120));
        } catch (Exception e) {
            log.warn("[RoutingDecisionPublisher] publish failed: {}", e.getMessage());
        }
    }
}
