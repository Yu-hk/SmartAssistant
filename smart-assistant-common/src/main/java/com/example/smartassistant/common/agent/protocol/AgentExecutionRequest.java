package com.example.smartassistant.common.agent.protocol;

import com.example.smartassistant.common.location.DeviceLocation;

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
        DeviceLocation deviceLocation) {

    public static final String CURRENT_VERSION = "1.0";

    public AgentExecutionRequest {
        protocolVersion = protocolVersion == null || protocolVersion.isBlank()
                ? CURRENT_VERSION : protocolVersion;
        operation = operation == null || operation.isBlank() ? "ANSWER" : operation;
        input = input != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(input)) : Map.of();
        contextRefs = contextRefs != null ? List.copyOf(contextRefs) : List.of();
        constraints = constraints != null ? List.copyOf(constraints) : List.of();
    }

    public static AgentExecutionRequest answer(String executionId, String userId,
                                               String question, DeviceLocation deviceLocation) {
        String safeQuestion = question == null ? "" : question;
        return new AgentExecutionRequest(
                CURRENT_VERSION, executionId, null, userId, "ANSWER", safeQuestion,
                Map.of("question", safeQuestion), List.of(), List.of(), null, null,
                deviceLocation);
    }

    /** Legacy map used by domain services during the compatibility window. */
    public Map<String, Object> toLegacyMap() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", question);
        if (userId != null) request.put("userId", userId);
        if (executionId != null) request.put("requestId", executionId);
        if (deviceLocation != null) request.put("deviceLocation", deviceLocation);
        return request;
    }
}
