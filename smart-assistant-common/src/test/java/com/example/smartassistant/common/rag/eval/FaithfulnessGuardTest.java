/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FaithfulnessGuard 单元测试——验证生产链路忠实度护栏的命中 / 未命中 / 跳过逻辑。
 */
class FaithfulnessGuardTest {

    private final FaithfulnessGuard guard = new FaithfulnessGuard();

    @Test
    @DisplayName("回答被上下文支撑 → 不触发，原样返回")
    void groundedAnswerNotFlagged() {
        String answer = "您的订单 ORD-123 已发货，预计 3 天送达。";
        String context = "订单 ORD-123 状态：已发货。物流：预计 3 天内送达。";

        FaithfulnessGuard.FaithfulnessVerdict v = guard.check(answer, context);
        assertTrue(v.checked());
        assertFalse(v.hallucination());
        assertNull(v.message());
        assertEquals(answer, guard.guard(answer, context));
    }

    @Test
    @DisplayName("回答含上下文未出现的数字 → 命中幻觉，追加免责声明")
    void numberMismatchTriggers() {
        String answer = "您订单的退款金额是 888 元。";
        String context = "退款规则：仅已发货订单可申请退款，金额以实际支付为准。";

        FaithfulnessGuard.FaithfulnessVerdict v = guard.check(answer, context);
        assertTrue(v.checked());
        assertTrue(v.hallucination());
        assertTrue(v.score() >= 0.6);
        assertNotNull(v.message());

        String guarded = guard.guard(answer, context);
        assertTrue(guarded.startsWith(answer));
        assertTrue(guarded.contains("未能在检索到的资料中核实"));
    }

    @Test
    @DisplayName("回答引用上下文未出现的实体 → 命中幻觉")
    void entityMismatchTriggers() {
        String answer = "推荐您购买「超能充电宝」，续航极佳。";
        String context = "我们提供多种移动电源，容量从 10000mAh 到 30000mAh。";

        FaithfulnessGuard.FaithfulnessVerdict v = guard.check(answer, context);
        assertTrue(v.checked());
        assertTrue(v.hallucination());
        assertNotNull(v.message());
    }

    @Test
    @DisplayName("无检索上下文 → 跳过校验，不误判")
    void emptyContextSkipped() {
        String answer = "今天天气不错。";

        FaithfulnessGuard.FaithfulnessVerdict v = guard.check(answer, "");
        assertFalse(v.checked());
        assertFalse(v.hallucination());
        assertEquals(answer, guard.guard(answer, null));
    }

    @Test
    @DisplayName("回答为空 → 跳过校验")
    void emptyAnswerSkipped() {
        FaithfulnessGuard.FaithfulnessVerdict v = guard.check("", "上下文内容");
        assertFalse(v.checked());
        assertFalse(v.hallucination());
    }

    @Test
    @DisplayName("自定义阈值：低于阈值不触发")
    void customHighThresholdSuppresses() {
        // 数字不匹配 rate≈0.7，阈值设为 0.9 则不触发
        FaithfulnessGuard strict = new FaithfulnessGuard(0.9, "请核实。");
        String answer = "价格是 888 元。";
        String context = "无相关数字。";

        FaithfulnessGuard.FaithfulnessVerdict v = strict.check(answer, context);
        assertFalse(v.hallucination());
    }
}
