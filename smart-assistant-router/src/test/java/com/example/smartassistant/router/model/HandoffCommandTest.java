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
        HandoffCommand cmd = HandoffCommand.fromFreeText(
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
        HandoffCommand cmd = HandoffCommand.fromFreeText(
                HandoffCommand.HandoffType.FAILED,
                "general_agent", "无法处理，转交", "错误: 权限不足");

        assertEquals(HandoffCommand.HandoffType.FAILED, cmd.handoffType());
    }

    @Test
    void structuredContextRendersSections() {
        HandoffCommand cmd = HandoffCommand.structured(
                HandoffCommand.HandoffType.HANDOFF,
                "order_agent", "请处理退款",
                "用户已通过实名认证",
                java.util.List.of("order/refund-api.md", "user/profile.json"),
                java.util.List.of("仅处理 7 日内订单", "禁止修改用户余额"),
                "orderId=10293, userId=881");

        String rendered = cmd.contextPayload();
        assertTrue(rendered.contains("【交接摘要】"));
        assertTrue(rendered.contains("【必读文件】"));
        assertTrue(rendered.contains("order/refund-api.md"));
        assertTrue(rendered.contains("【关键约束】"));
        assertTrue(rendered.contains("仅处理 7 日内订单"));
        assertTrue(rendered.contains("【关键数据】"));
        assertTrue(rendered.contains("orderId=10293"));
        // 旧访问器语义保持：渲染文本仍包含摘要内容
        assertTrue(rendered.contains("实名认证"));
    }

    @Test
    void freeTextStillParsable() {
        HandoffCommand cmd = HandoffCommand.fromFreeText(
                HandoffCommand.HandoffType.HANDOFF, "order_agent", "请处理退款", "之前已确认订单信息");
        assertTrue(cmd.contextPayload().contains("已确认"));
    }
}
