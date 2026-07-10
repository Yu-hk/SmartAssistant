/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TokenUsageCache} 单元测试。
 * <p>
 * 验证零装配 Token 用量缓存的 record/consume 合约。
 * </p>
 */
@DisplayName("TokenUsageCache 单元测试")
class TokenUsageCacheTest {

    @Test
    @DisplayName("record 后 consume 返回正确的 TokenUsage")
    void recordThenConsume_returnsCorrect() {
        TokenUsageCache.record("req-001", 10, 20, 30);

        var usage = TokenUsageCache.consume("req-001");
        assertNotNull(usage);
        assertAll(
                () -> assertEquals(10, usage.promptTokens()),
                () -> assertEquals(20, usage.completionTokens()),
                () -> assertEquals(30, usage.totalTokens())
        );
    }

    @Test
    @DisplayName("consume 后缓存被清除（幂等删除）")
    void consume_removesFromCache() {
        TokenUsageCache.record("req-002", 5, 15, 20);
        assertNotNull(TokenUsageCache.consume("req-002"));
        assertNull(TokenUsageCache.consume("req-002"), "二次 consume 应返回 null");
    }

    @Test
    @DisplayName("未 record → consume 返回 null")
    void consumeUnrecorded_returnsNull() {
        assertNull(TokenUsageCache.consume("nonexistent"));
    }

    @Test
    @DisplayName("record 不存在的 requestId → 静默忽略")
    void recordNullRequestId_ignored() {
        assertDoesNotThrow(() -> TokenUsageCache.record(null, 0, 0, 0));
        assertDoesNotThrow(() -> TokenUsageCache.record(" ", 0, 0, 0));
    }

    @Test
    @DisplayName("相同 requestId 多次 record → 保留最后一次")
    void recordMultiple_overwrites() {
        TokenUsageCache.record("req-003", 1, 1, 2);
        TokenUsageCache.record("req-003", 10, 20, 30);

        var usage = TokenUsageCache.consume("req-003");
        assertNotNull(usage);
        assertEquals(30, usage.totalTokens(), "应保留最后一次记录的 totalTokens");
    }

    @Test
    @DisplayName("consume null/空白 requestId → 返回 null")
    void consumeInvalidKey_returnsNull() {
        assertNull(TokenUsageCache.consume(null));
        assertNull(TokenUsageCache.consume(""));
    }
}
