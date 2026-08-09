/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.infrastructure;

import java.util.Map;

/**
 * Extracts provider-reported token usage propagated by the Router service.
 *
 * <p>Token usage crosses service/JVM boundaries in the Router response. The
 * Consumer must therefore never rely on its process-local {@code
 * TokenUsageCache}. This extractor accepts the current camel-case contract and
 * the common snake-case / nested usage variants to keep rolling deployments
 * compatible.</p>
 */
public final class TokenUsageExtractor {

    private TokenUsageExtractor() {}

    public static TokenUsage extract(Map<?, ?> response) {
        if (response == null || response.isEmpty()) {
            return TokenUsage.unknown();
        }

        TokenUsage direct = fromMap(response);
        if (direct.tracked()) {
            return direct;
        }

        for (String key : new String[]{"tokenUsage", "token_usage", "usage"}) {
            Object nested = response.get(key);
            if (nested instanceof Map<?, ?> map) {
                TokenUsage usage = fromMap(map);
                if (usage.tracked()) {
                    return usage;
                }
            }
        }

        Object metadata = response.get("metadata");
        if (metadata instanceof Map<?, ?> metadataMap) {
            TokenUsage metadataUsage = fromMap(metadataMap);
            if (metadataUsage.tracked()) {
                return metadataUsage;
            }
            for (String key : new String[]{"tokenUsage", "token_usage", "usage"}) {
                Object nested = metadataMap.get(key);
                if (nested instanceof Map<?, ?> map) {
                    TokenUsage usage = fromMap(map);
                    if (usage.tracked()) {
                        return usage;
                    }
                }
            }
        }
        return TokenUsage.unknown();
    }

    /**
     * Adds two independently measured usage snapshots for a path known to have
     * executed both stages. If either stage is untracked, the aggregate is
     * unknown rather than a misleading partial total. Component totals remain
     * unknown unless both tracked snapshots reported that component.
     */
    public static TokenUsage merge(TokenUsage left, TokenUsage right) {
        TokenUsage first = left == null ? TokenUsage.unknown() : left;
        TokenUsage second = right == null ? TokenUsage.unknown() : right;
        if (!first.tracked() || !second.tracked()) return TokenUsage.unknown();
        return new TokenUsage(
                addComplete(first.promptTokens(), second.promptTokens()),
                addComplete(first.completionTokens(), second.completionTokens()),
                safeAdd(first.totalTokens(), second.totalTokens()));
    }

    private static TokenUsage fromMap(Map<?, ?> values) {
        Long prompt = firstLong(values,
                "promptTokens", "prompt_tokens", "inputTokens", "input_tokens");
        Long completion = firstLong(values,
                "completionTokens", "completion_tokens", "outputTokens", "output_tokens");
        Long total = firstLong(values, "totalTokens", "total_tokens");

        // A total can be derived only when both components are known. Treating
        // a missing component as zero would turn incomplete telemetry into a
        // deceptively precise number.
        if (total == null && prompt != null && completion != null) {
            total = safeAdd(prompt, completion);
        }
        return new TokenUsage(prompt, completion, total);
    }

    private static Long firstLong(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            if (!values.containsKey(key)) continue;
            Long parsed = nonNegativeLong(values.get(key));
            if (parsed != null) return parsed;
        }
        return null;
    }

    private static Long nonNegativeLong(Object value) {
        if (value instanceof Number number) {
            long parsed = number.longValue();
            return parsed >= 0 ? parsed : null;
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                long parsed = Long.parseLong(string.trim());
                return parsed >= 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static Long addComplete(Long left, Long right) {
        return left != null && right != null ? safeAdd(left, right) : null;
    }

    public record TokenUsage(Long promptTokens, Long completionTokens, Long totalTokens) {
        public static TokenUsage unknown() {
            return new TokenUsage(null, null, null);
        }

        public boolean tracked() {
            return totalTokens != null;
        }

        /** Copies only measured values; absence remains JSON/database NULL. */
        public void copyTo(Map<String, Object> target) {
            if (promptTokens != null) target.put("promptTokens", promptTokens);
            if (completionTokens != null) target.put("completionTokens", completionTokens);
            if (totalTokens != null) target.put("totalTokens", totalTokens);
        }
    }
}
