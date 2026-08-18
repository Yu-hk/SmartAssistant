package com.example.smartassistant.common.agent.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable output envelope passed from an upstream workflow node to its dependants. */
public record AgentNodeOutput(
        String nodeId,
        String agentName,
        String status,
        String answer,
        Map<String, Object> data) {

    public AgentNodeOutput {
        status = status == null || status.isBlank() ? "UNKNOWN" : status;
        data = data == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
