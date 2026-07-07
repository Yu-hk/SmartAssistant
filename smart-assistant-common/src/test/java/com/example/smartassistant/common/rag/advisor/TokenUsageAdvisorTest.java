/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TokenUsageAdvisor} 单元测试。
 *
 * @author Yu-hk
 * @since 2026-07-07
 */
class TokenUsageAdvisorTest {

    private final TokenUsageAdvisor advisor = new TokenUsageAdvisor();

    @Test
    @DisplayName("name 返回 'TokenUsageAdvisor'")
    void testName() {
        assertEquals("TokenUsageAdvisor", advisor.getName());
    }

    @Test
    @DisplayName("Order 为 350")
    void testOrder() {
        assertEquals(350, advisor.getOrder());
    }
}
