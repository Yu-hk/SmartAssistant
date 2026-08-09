/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 */

package com.example.smartassistant.consumer.service.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenUsageExtractorTest {

    @Test
    void readsCurrentCamelCaseContractIncludingMeasuredZero() {
        var usage = TokenUsageExtractor.extract(Map.of(
                "promptTokens", 0,
                "completionTokens", 0,
                "totalTokens", 0));

        assertTrue(usage.tracked());
        assertEquals(0L, usage.promptTokens());
        assertEquals(0L, usage.completionTokens());
        assertEquals(0L, usage.totalTokens());
    }

    @Test
    void acceptsNestedSnakeCaseAndDerivesTotalOnlyFromBothComponents() {
        var usage = TokenUsageExtractor.extract(Map.of("usage", Map.of(
                "prompt_tokens", "12",
                "completion_tokens", 8)));

        assertEquals(12L, usage.promptTokens());
        assertEquals(8L, usage.completionTokens());
        assertEquals(20L, usage.totalTokens());
    }

    @Test
    void missingTelemetryRemainsUnknownInsteadOfBecomingZero() {
        var usage = TokenUsageExtractor.extract(Map.of("result", "cached text"));

        assertFalse(usage.tracked());
        assertNull(usage.promptTokens());
        assertNull(usage.completionTokens());
        assertNull(usage.totalTokens());
    }

    @Test
    void mergeAddsRouterAndAgentUsageWithoutInventingMissingComponents() {
        TokenUsageExtractor.TokenUsage complete = TokenUsageExtractor.merge(
                new TokenUsageExtractor.TokenUsage(20L, 5L, 25L),
                new TokenUsageExtractor.TokenUsage(30L, 10L, 40L));
        assertEquals(50L, complete.promptTokens());
        assertEquals(15L, complete.completionTokens());
        assertEquals(65L, complete.totalTokens());

        TokenUsageExtractor.TokenUsage partialComponents = TokenUsageExtractor.merge(
                new TokenUsageExtractor.TokenUsage(null, null, 25L),
                new TokenUsageExtractor.TokenUsage(30L, 10L, 40L));
        assertNull(partialComponents.promptTokens());
        assertNull(partialComponents.completionTokens());
        assertEquals(65L, partialComponents.totalTokens());

        TokenUsageExtractor.TokenUsage incompleteStage = TokenUsageExtractor.merge(
                TokenUsageExtractor.TokenUsage.unknown(),
                new TokenUsageExtractor.TokenUsage(30L, 10L, 40L));
        assertFalse(incompleteStage.tracked());
        assertNull(incompleteStage.totalTokens());
    }
}
