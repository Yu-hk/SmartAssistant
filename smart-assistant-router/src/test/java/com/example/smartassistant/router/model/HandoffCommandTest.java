/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HandoffCommand} 单元测试。
 */
class HandoffCommandTest {

    @Test
    void handoffCreation() {
        HandoffCommand cmd = new HandoffCommand(
                HandoffCommand.HandoffType.HANDOFF,
                "order_agent",
                "请处理退款",
                "之前已确认订单信息");

        assertEquals(HandoffCommand.HandoffType.HANDOFF, cmd.handoffType());
        assertEquals("order_agent", cmd.targetAgent());
        assertEquals("请处理退款", cmd.question());
        assertTrue(cmd.contextPayload().contains("已确认"));
    }

    @Test
    void completeType() {
        HandoffCommand cmd = new HandoffCommand(
                HandoffCommand.HandoffType.COMPLETE,
                null, "任务完成", null);

        assertEquals(HandoffCommand.HandoffType.COMPLETE, cmd.handoffType());
    }

    @Test
    void failedType() {
        HandoffCommand cmd = new HandoffCommand(
                HandoffCommand.HandoffType.FAILED,
                "general_agent", "无法处理，转交", "错误: 权限不足");

        assertEquals(HandoffCommand.HandoffType.FAILED, cmd.handoffType());
    }
}
