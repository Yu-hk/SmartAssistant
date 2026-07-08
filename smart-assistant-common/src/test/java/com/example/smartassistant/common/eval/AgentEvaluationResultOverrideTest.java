/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link AgentEvaluationResult} 的 {@code overridePassed} 机制：
 * 评测增强管线（Trial×pass^k）以稳定性概率决定单用例通过，需绕过复合分阈值。
 */
class AgentEvaluationResultOverrideTest {

    /** 自然不通过（复合分<0.6）但显式覆盖为通过。 */
    @Test
    void overridePassedTrueBeatsNaturalFail() {
        AgentEvaluationResult r = new AgentEvaluationResult.Builder("OV-1")
                .expectedIntent("order_query")
                .expectedTools(List.of("queryOrder"))   // 设预期工具，实际未调用 → 工具准确率 0
                .expectedKeywords(List.of("订单"))
                .actualResponse("无关内容")            // 无关键词 → 质量分 0
                .actualToolsCalled(List.of())          // 无工具 → 工具准确率 0
                .overridePassed(true)
                .build();
        assertFalse(r.passed() == r.getCompositeScore() >= 0.6, "前置：自然判定应失败");
        assertTrue(r.passed(), "overridePassed=true 应强制通过");
    }

    /** 自然通过但显式覆盖为不通过。 */
    @Test
    void overridePassedFalseBeatsNaturalPass() {
        AgentEvaluationResult r = new AgentEvaluationResult.Builder("OV-2")
                .expectedIntent("order_query")
                .expectedKeywords(List.of("订单"))
                .actualIntent("order_query")
                .actualResponse("您的订单已发货，订单状态可查询")
                .actualToolsCalled(List.of("queryOrder"))
                .overridePassed(false)
                .build();
        assertTrue(r.getCompositeScore() >= 0.6, "前置：自然判定应通过");
        assertFalse(r.passed(), "overridePassed=false 应强制不通过");
    }

    /** 未设置 overridePassed 时走原复合分逻辑（向后兼容）。 */
    @Test
    void noOverrideFallsBackToComposite() {
        AgentEvaluationResult pass = new AgentEvaluationResult.Builder("OV-3")
                .expectedIntent("order_query")
                .expectedKeywords(List.of("订单"))
                .actualIntent("order_query")
                .actualResponse("您的订单已发货，订单状态可查询")
                .actualToolsCalled(List.of("queryOrder"))
                .build();
        assertTrue(pass.passed(), "自然通过用例应判定通过");

        AgentEvaluationResult fail = new AgentEvaluationResult.Builder("OV-4")
                .expectedIntent("order_query")
                .expectedTools(List.of("queryOrder"))   // 设预期工具，实际未调用 → 工具准确率 0
                .expectedKeywords(List.of("订单"))
                .actualResponse("无关内容")
                .actualToolsCalled(List.of())
                .build();
        assertFalse(fail.passed(), "自然失败用例应判定不通过");
    }
}
