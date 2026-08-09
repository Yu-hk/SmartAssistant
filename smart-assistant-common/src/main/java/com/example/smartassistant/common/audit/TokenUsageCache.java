/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.audit;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Request-scoped, in-process token accumulator.
 *
 * <p>Every measured model invocation is added to its request aggregate. A
 * call id makes event redelivery idempotent. Missing components remain null;
 * a partial sum is never presented as a complete measurement. Service
 * boundaries must explicitly propagate the aggregate because this class is
 * deliberately not a distributed cache.</p>
 */
public final class TokenUsageCache {

    private static final ConcurrentHashMap<String, Aggregate> CACHE = new ConcurrentHashMap<>();
    private static final long ENTRY_TTL_MILLIS = 30 * 60 * 1000L;
    private static final int MAX_REQUESTS = 50_000;
    private static final AtomicLong RECORD_COUNT = new AtomicLong();

    private TokenUsageCache() {
    }

    public static void record(String requestId, int promptTokens, int completionTokens, int totalTokens) {
        record(requestId, (long) promptTokens, completionTokens, totalTokens);
    }

    public static void record(String requestId, long promptTokens, long completionTokens, long totalTokens) {
        recordPartial(requestId, UUID.randomUUID().toString(), promptTokens, completionTokens, totalTokens);
    }

    public static void record(String requestId, String callId,
                              long promptTokens, long completionTokens, long totalTokens) {
        recordPartial(requestId, callId, promptTokens, completionTokens, totalTokens);
    }

    /** Records a measured invocation while preserving unknown components. */
    public static void recordPartial(String requestId, String callId,
                                     Long promptTokens, Long completionTokens, Long totalTokens) {
        if (!validKey(requestId) || callId == null || callId.isBlank()) return;
        Long prompt = nonNegative(promptTokens);
        Long completion = nonNegative(completionTokens);
        Long total = nonNegative(totalTokens);
        if (total == null && prompt != null && completion != null) {
            total = addSaturated(prompt, completion);
        }
        // A lone prompt/completion value is not sufficient to measure a call.
        if (total == null) return;
        maintainBounds(requestId, System.currentTimeMillis());
        TokenUsage usage = new TokenUsage(prompt, completion, total);
        CACHE.compute(requestId, (ignored, current) -> {
            Aggregate aggregate = current != null ? current : new Aggregate();
            aggregate.add(callId, usage);
            return aggregate;
        });
    }

    public static void record(String requestId, String callId, TokenUsage usage) {
        if (usage == null) return;
        recordPartial(requestId, callId,
                usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
    }

    /**
     * Marks the request aggregate as incomplete. This is used at service
     * boundaries when an LLM-capable downstream call did not return measurable
     * telemetry; later measured calls must not turn that partial sum into an
     * apparently complete conversation total.
     */
    public static void markIncomplete(String requestId) {
        if (!validKey(requestId)) return;
        maintainBounds(requestId, System.currentTimeMillis());
        CACHE.compute(requestId, (ignored, current) -> {
            Aggregate aggregate = current != null ? current : new Aggregate();
            aggregate.markIncomplete();
            return aggregate;
        });
    }

    public static TokenUsage consume(String requestId) {
        if (!validKey(requestId)) return null;
        Aggregate aggregate = CACHE.remove(requestId);
        return aggregate != null ? aggregate.snapshot() : null;
    }

    public static TokenUsage snapshot(String requestId) {
        if (!validKey(requestId)) return null;
        Aggregate aggregate = CACHE.get(requestId);
        return aggregate != null ? aggregate.snapshot() : null;
    }

    public record TokenUsage(Long promptTokens, Long completionTokens, Long totalTokens) {
    }

    private static boolean validKey(String requestId) {
        return requestId != null && !requestId.isBlank() && !"-".equals(requestId);
    }

    private static Long nonNegative(Long value) {
        return value != null && value >= 0 ? value : null;
    }

    private static long addSaturated(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static Long addComplete(Long left, Long right) {
        return left != null && right != null ? addSaturated(left, right) : null;
    }

    private static void maintainBounds(String requestId, long now) {
        long records = RECORD_COUNT.incrementAndGet();
        if ((records & 0xff) == 0) cleanupExpired(now);
        if (CACHE.containsKey(requestId) || CACHE.size() < MAX_REQUESTS) return;

        synchronized (CACHE) {
            cleanupExpired(now);
            while (!CACHE.containsKey(requestId) && CACHE.size() >= MAX_REQUESTS) {
                String oldestKey = null;
                long oldestUpdate = Long.MAX_VALUE;
                for (var entry : CACHE.entrySet()) {
                    long updatedAt = entry.getValue().lastUpdatedAt();
                    if (updatedAt < oldestUpdate) {
                        oldestUpdate = updatedAt;
                        oldestKey = entry.getKey();
                    }
                }
                if (oldestKey == null) break;
                CACHE.remove(oldestKey);
            }
        }
    }

    static void cleanupExpired(long now) {
        long cutoff = now - ENTRY_TTL_MILLIS;
        CACHE.entrySet().removeIf(entry -> entry.getValue().lastUpdatedAt() < cutoff);
    }

    private static final class Aggregate {
        private final Set<String> callIds = ConcurrentHashMap.newKeySet();
        private boolean initialized;
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        private volatile long lastUpdatedAt = System.currentTimeMillis();
        private boolean complete = true;

        synchronized void add(String callId, TokenUsage usage) {
            if (!callIds.add(callId)) return;
            if (!initialized) {
                promptTokens = usage.promptTokens();
                completionTokens = usage.completionTokens();
                totalTokens = usage.totalTokens();
                initialized = true;
                return;
            }
            promptTokens = addComplete(promptTokens, usage.promptTokens());
            completionTokens = addComplete(completionTokens, usage.completionTokens());
            totalTokens = addComplete(totalTokens, usage.totalTokens());
            lastUpdatedAt = System.currentTimeMillis();
        }

        synchronized TokenUsage snapshot() {
            return initialized && complete
                    ? new TokenUsage(promptTokens, completionTokens, totalTokens) : null;
        }

        synchronized void markIncomplete() {
            complete = false;
            lastUpdatedAt = System.currentTimeMillis();
        }

        long lastUpdatedAt() {
            return lastUpdatedAt;
        }
    }
}
