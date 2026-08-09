/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenUsageCacheTest {

    @Test
    void recordThenConsumeReturnsAggregate() {
        TokenUsageCache.record("req-001", 10, 20, 30);

        var usage = TokenUsageCache.consume("req-001");
        assertNotNull(usage);
        assertAll(
                () -> assertEquals(10, usage.promptTokens()),
                () -> assertEquals(20, usage.completionTokens()),
                () -> assertEquals(30, usage.totalTokens()));
    }

    @Test
    void consumeRemovesValue() {
        TokenUsageCache.record("req-002", 5, 15, 20);
        assertNotNull(TokenUsageCache.consume("req-002"));
        assertNull(TokenUsageCache.consume("req-002"));
    }

    @Test
    void distinctCallsAccumulate() {
        TokenUsageCache.record("req-003", "call-1", 1, 1, 2);
        TokenUsageCache.record("req-003", "call-2", 10, 20, 30);

        var usage = TokenUsageCache.consume("req-003");
        assertNotNull(usage);
        assertAll(
                () -> assertEquals(11, usage.promptTokens()),
                () -> assertEquals(21, usage.completionTokens()),
                () -> assertEquals(32, usage.totalTokens()));
    }

    @Test
    void duplicateCallIdIsIdempotentAndSnapshotDoesNotConsume() {
        TokenUsageCache.record("req-004", "call-1", 4, 6, 10);
        TokenUsageCache.record("req-004", "call-1", 4, 6, 10);

        assertEquals(10, TokenUsageCache.snapshot("req-004").totalTokens());
        assertEquals(10, TokenUsageCache.consume("req-004").totalTokens());
        assertNull(TokenUsageCache.snapshot("req-004"));
    }

    @Test
    void partialComponentsNeverBecomeDeceptivelyComplete() {
        TokenUsageCache.recordPartial("req-005", "call-1", 10L, 2L, 12L);
        TokenUsageCache.recordPartial("req-005", "call-2", null, null, 7L);

        var usage = TokenUsageCache.consume("req-005");
        assertNull(usage.promptTokens());
        assertNull(usage.completionTokens());
        assertEquals(19, usage.totalTokens());
    }

    @Test
    void derivesTotalOnlyWhenBothComponentsAreKnown() {
        TokenUsageCache.recordPartial("req-006", "complete", 9L, 4L, null);
        TokenUsageCache.recordPartial("req-007", "partial", 9L, null, null);

        assertEquals(13, TokenUsageCache.consume("req-006").totalTokens());
        assertNull(TokenUsageCache.consume("req-007"));
    }

    @Test
    void invalidOrAnonymousTraceKeysAreIgnored() {
        assertDoesNotThrow(() -> TokenUsageCache.record(null, 0, 0, 0));
        assertDoesNotThrow(() -> TokenUsageCache.record(" ", 0, 0, 0));
        assertDoesNotThrow(() -> TokenUsageCache.record("-", 1, 2, 3));
        assertNull(TokenUsageCache.consume(null));
        assertNull(TokenUsageCache.consume(""));
        assertNull(TokenUsageCache.consume("-"));
        assertNull(TokenUsageCache.consume("missing"));
    }

    @Test
    void staleUnconsumedRequestsAreEvicted() {
        TokenUsageCache.record("req-stale", 3, 2, 5);

        TokenUsageCache.cleanupExpired(Long.MAX_VALUE);

        assertNull(TokenUsageCache.consume("req-stale"));
    }

    @Test
    void incompleteDownstreamInvalidatesOtherwiseMeasuredAggregate() {
        TokenUsageCache.record("req-incomplete", "router-call", 7, 3, 10);

        TokenUsageCache.markIncomplete("req-incomplete");
        TokenUsageCache.record("req-incomplete", "later-call", 5, 2, 7);

        assertNull(TokenUsageCache.consume("req-incomplete"));
    }
}
