package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.audit.TokenUsageCache;
import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.routing.contract.RoutingKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
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

    public void publish(String requestId, RoutingResult routingResult,
                        TokenUsageCache.TokenUsage tokenUsage,
                        ToolUsageCache.ToolUsage toolUsage) {
        if (redisTemplate == null || requestId == null || requestId.isBlank()
                || routingResult == null) return;
        try {
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("requestId", requestId);
            if (routingResult.getAgentName() != null
                    && !routingResult.getAgentName().isBlank()) {
                decision.put("agentName", routingResult.getAgentName());
            }
            RoutingResult.ExecutionMode executionMode = routingResult.getExecutionMode() != null
                    ? routingResult.getExecutionMode() : RoutingResult.ExecutionMode.BUILTIN;
            RoutingResult.WorkflowStatus workflowStatus = routingResult.getWorkflowStatus() != null
                    ? routingResult.getWorkflowStatus() : RoutingResult.WorkflowStatus.COMPLETED;
            decision.put("executionMode", executionMode.name());
            decision.put("participatingAgents", routingResult.getParticipatingAgents() != null
                    ? routingResult.getParticipatingAgents() : List.of());
            decision.put("workflowStatus", workflowStatus.name());
            decision.put("confidence", routingResult.getConfidence());
            decision.put("result", routingResult.getResult());
            decision.put("intentTag", routingResult.getIntentTag());
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
