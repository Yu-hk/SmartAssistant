package com.example.smartassistant.service.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrderRagServiceOrderIdTest {

    @Test
    void extractsMultiSegmentOrderId() {
        assertEquals(
                "ORD-E2E-0001",
                OrderRagService.extractOrderId("帮我查询订单 ORD-E2E-0001 的物流状态"));
    }

    @Test
    void keepsLegacySingleSegmentOrderId() {
        assertEquals("ORD-123", OrderRagService.extractOrderId("查询 ORD-123"));
    }

    @Test
    void rejectsMissingOrderId() {
        assertNull(OrderRagService.extractOrderId("帮我查询最近一笔订单"));
    }
}
