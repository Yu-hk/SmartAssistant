package com.example.smartassistant.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Compact JSON transport for tool telemetry between Agent and Router. */
public final class ToolUsageHeaders {

    public static final String TOOL_USAGE = "X-Agent-Tool-Usage";
    private static final int MAX_HEADER_LENGTH = 8_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolUsageHeaders() {
    }

    public static String encode(ToolUsageCache.ToolUsage usage) {
        if (usage == null) return null;
        try {
            String value = MAPPER.writeValueAsString(usage);
            if (value.length() <= MAX_HEADER_LENGTH) return value;
            return MAPPER.writeValueAsString(new ToolUsageCache.ToolUsage(false, usage.calls().stream()
                    .limit(8).toList()));
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    public static ToolUsageCache.ToolUsage decode(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_HEADER_LENGTH) return null;
        try {
            return MAPPER.readValue(value, ToolUsageCache.ToolUsage.class);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }
}
