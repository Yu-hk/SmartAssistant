package com.example.smartassistant.common.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-scoped tool invocation telemetry shared inside one service JVM.
 *
 * <p>The cache deliberately records only the tool name, outcome and duration.
 * Arguments and results may contain credentials or business data and therefore
 * never enter the administration audit payload.</p>
 */
public final class ToolUsageCache {

    private static final int MAX_REQUESTS = 50_000;
    private static final int MAX_CALLS_PER_REQUEST = 32;
    private static final long TTL_MILLIS = 30 * 60 * 1000L;
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private ToolUsageCache() {
    }

    /** Marks a request as measured, even when it ultimately invokes no tools. */
    public static void start(String requestId) {
        if (!valid(requestId)) return;
        evictIfNeeded();
        CACHE.computeIfAbsent(requestId, ignored -> new Entry());
    }

    public static void record(String requestId, String toolName, boolean success, long durationMs) {
        if (!valid(requestId) || toolName == null || toolName.isBlank()) return;
        start(requestId);
        Entry entry = CACHE.get(requestId);
        if (entry == null) return;
        synchronized (entry) {
            if (entry.calls.size() < MAX_CALLS_PER_REQUEST) {
                entry.calls.add(new ToolCall(sanitizeName(toolName),
                        success ? "SUCCESS" : "FAILED", Math.max(0, durationMs)));
            } else {
                entry.complete = false;
            }
            entry.updatedAt = System.currentTimeMillis();
        }
    }

    /** Merges telemetry returned by another service in the same request chain. */
    public static void merge(String requestId, ToolUsage usage) {
        if (!valid(requestId) || usage == null) return;
        start(requestId);
        Entry entry = CACHE.get(requestId);
        if (entry == null) return;
        synchronized (entry) {
            entry.complete &= usage.complete();
            for (ToolCall call : usage.calls()) {
                if (entry.calls.size() >= MAX_CALLS_PER_REQUEST) {
                    entry.complete = false;
                    break;
                }
                if (call != null && call.name() != null && !call.name().isBlank()) {
                    entry.calls.add(new ToolCall(sanitizeName(call.name()),
                            normalizeStatus(call.status()), Math.max(0, call.durationMs())));
                }
            }
            entry.updatedAt = System.currentTimeMillis();
        }
    }

    public static void markIncomplete(String requestId) {
        if (!valid(requestId)) return;
        start(requestId);
        Entry entry = CACHE.get(requestId);
        if (entry != null) {
            entry.complete = false;
            entry.updatedAt = System.currentTimeMillis();
        }
    }

    public static ToolUsage snapshot(String requestId) {
        if (!valid(requestId)) return null;
        Entry entry = CACHE.get(requestId);
        if (entry == null) return null;
        if (expired(entry)) {
            CACHE.remove(requestId, entry);
            return null;
        }
        synchronized (entry) {
            return new ToolUsage(entry.complete, List.copyOf(entry.calls));
        }
    }

    public static ToolUsage consume(String requestId) {
        if (!valid(requestId)) return null;
        Entry entry = CACHE.remove(requestId);
        if (entry == null || expired(entry)) return null;
        synchronized (entry) {
            return new ToolUsage(entry.complete, List.copyOf(entry.calls));
        }
    }

    static void clear() {
        CACHE.clear();
    }

    private static boolean valid(String requestId) {
        return requestId != null && !requestId.isBlank() && !"-".equals(requestId);
    }

    private static boolean expired(Entry entry) {
        return System.currentTimeMillis() - entry.updatedAt > TTL_MILLIS;
    }

    private static void evictIfNeeded() {
        if (CACHE.size() < MAX_REQUESTS) return;
        long now = System.currentTimeMillis();
        CACHE.entrySet().removeIf(item -> now - item.getValue().updatedAt > TTL_MILLIS);
        if (CACHE.size() < MAX_REQUESTS) return;
        CACHE.entrySet().stream()
                .min((left, right) -> Long.compare(left.getValue().updatedAt, right.getValue().updatedAt))
                .ifPresent(oldest -> CACHE.remove(oldest.getKey(), oldest.getValue()));
    }

    private static String sanitizeName(String value) {
        String normalized = value.trim().replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private static String normalizeStatus(String value) {
        return "SUCCESS".equalsIgnoreCase(value) ? "SUCCESS" : "FAILED";
    }

    public record ToolCall(String name, String status, long durationMs) {
    }

    public record ToolUsage(boolean complete, List<ToolCall> calls) {
        public ToolUsage {
            calls = calls == null ? List.of() : List.copyOf(calls);
        }
    }

    private static final class Entry {
        private final List<ToolCall> calls = new ArrayList<>();
        private volatile boolean complete = true;
        private volatile long updatedAt = System.currentTimeMillis();
    }
}
