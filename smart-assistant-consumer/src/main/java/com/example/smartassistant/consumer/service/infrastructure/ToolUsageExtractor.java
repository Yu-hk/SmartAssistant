package com.example.smartassistant.consumer.service.infrastructure;

import com.example.smartassistant.common.audit.ToolUsageCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Normalizes tool telemetry from Router JSON maps. */
public final class ToolUsageExtractor {

    private ToolUsageExtractor() {
    }

    public static ToolUsageCache.ToolUsage extract(Map<String, Object> source) {
        if (source == null || !source.containsKey("toolUsageComplete")) return null;
        Object completeValue = source.get("toolUsageComplete");
        if (!(completeValue instanceof Boolean complete)) return null;
        List<ToolUsageCache.ToolCall> calls = new ArrayList<>();
        Object rawCalls = source.get("toolCalls");
        if (rawCalls instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                ToolUsageCache.ToolCall call = parseCall(item);
                if (call != null) calls.add(call);
            }
        }
        return new ToolUsageCache.ToolUsage(complete, calls);
    }

    public static void copyTo(ToolUsageCache.ToolUsage usage, Map<String, Object> target) {
        if (usage == null || target == null) return;
        target.put("toolUsageComplete", usage.complete());
        target.put("toolCalls", usage.calls());
        target.put("toolInvoked", !usage.calls().isEmpty());
    }

    private static ToolUsageCache.ToolCall parseCall(Object value) {
        if (value instanceof ToolUsageCache.ToolCall call) return call;
        if (!(value instanceof Map<?, ?> map)) return null;
        String name = text(map.get("name"));
        if (name.isBlank()) return null;
        String status = text(map.get("status"));
        long duration = number(map.get("durationMs"));
        if (duration == 0) duration = number(map.get("duration_ms"));
        return new ToolUsageCache.ToolCall(name,
                "SUCCESS".equalsIgnoreCase(status) ? "SUCCESS" : "FAILED",
                Math.max(0, duration));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? 0 : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
