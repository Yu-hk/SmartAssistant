package com.example.smartassistant.common.agent.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned, domain-neutral request used for Router-to-Agent communication. */
public record AgentExecutionRequest(
        String protocolVersion,
        String executionId,
        String nodeId,
        String userId,
        String operation,
        String question,
        Map<String, Object> input,
        List<String> contextRefs,
        List<String> constraints,
        Long deadlineEpochMs,
        String idempotencyKey,
        Map<String, AgentNodeOutput> predecessorOutputs,
        String workflowKey,
        Integer workflowVersion,
        String workflowChecksum,
        int attempt,
        String traceId) {

    public static final String CURRENT_VERSION = "1.0";

    public AgentExecutionRequest {
        protocolVersion = protocolVersion == null || protocolVersion.isBlank()
                ? CURRENT_VERSION : protocolVersion;
        operation = operation == null || operation.isBlank() ? "ANSWER" : operation;
        input = input != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(input)) : Map.of();
        contextRefs = contextRefs != null ? List.copyOf(contextRefs) : List.of();
        constraints = constraints != null ? List.copyOf(constraints) : List.of();
        predecessorOutputs = predecessorOutputs != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(predecessorOutputs)) : Map.of();
        attempt = Math.max(0, attempt);
        traceId = traceId == null || traceId.isBlank() ? executionId : traceId;
    }

    /** Compatibility constructor for protocol 1.0 callers. */
    public AgentExecutionRequest(
            String protocolVersion, String executionId, String nodeId, String userId,
            String operation, String question, Map<String, Object> input,
            List<String> contextRefs, List<String> constraints, Long deadlineEpochMs,
            String idempotencyKey) {
        this(protocolVersion, executionId, nodeId, userId, operation, question, input,
                contextRefs, constraints, deadlineEpochMs, idempotencyKey,
                Map.of(), null, null, null, 0, executionId);
    }

    public static AgentExecutionRequest answer(String executionId, String userId,
                                               String question) {
        String safeQuestion = question == null ? "" : question;
        return new AgentExecutionRequest(
                CURRENT_VERSION, executionId, null, userId, "ANSWER", safeQuestion,
                Map.of("question", safeQuestion), List.of(), List.of(), null, null);
    }

    /** Legacy map used by domain services during the compatibility window. */
    public Map<String, Object> toLegacyMap() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", question);
        if (userId != null) request.put("userId", userId);
        if (executionId != null) request.put("requestId", executionId);
        return request;
    }
}
