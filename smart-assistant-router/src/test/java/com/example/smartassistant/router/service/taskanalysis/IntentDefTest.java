/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.taskanalysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IntentDef} 与 {@link IntentRetriever} 的单元测试。
 */
class IntentDefTest {

    @Test
    void intentDef_creation() {
        IntentDef def = new IntentDef("ORDER", "订单/物流/退款",
                "用户查询订单状态", List.of("订单", "物流"),
                "示例", "相关工具: query_order");

        assertEquals("ORDER", def.id());
        assertEquals("订单/物流/退款", def.name());
        assertEquals(2, def.keywords().size());
        assertTrue(def.keywords().contains("订单"));
    }
}
