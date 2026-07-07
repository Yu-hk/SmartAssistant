/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AgentEvaluationResult} 的单元测试。
 *
 * @author Yu-hk
 * @since 2026-07-06
 */
class AgentEvaluationResultTest {

    @Test
    @DisplayName("完全通过的 Agent 评测")
    void testPerfectAgentEval() {
        AgentEvaluationResult result = new AgentEvaluationResult.Builder("T-001")
                .caseName("查询订单状态")
                .agentName("order")
                .input("我的订单到哪了？")
                .expectedIntent("order_query")
                .actualIntent("order_query")
                .expectedTools(List.of("queryOrder"))
                .actualToolsCalled(List.of("queryOrder"))
                .actualToolCallCount(1)
                .actualIterations(2)
                .actualLatencyMs(1500)
                .totalTokens(512)
                .expectedKeywords(List.of("订单", "配送"))
                .actualResponse("您的订单正在配送中，预计明天到达。")
                .build();

        assertTrue(result.passed());
        assertEquals(1.0, result.getIntentMatchScore(), 0.001);
        assertEquals(1.0, result.getToolSelectionAccuracy(), 0.001);
        assertTrue(result.getCompositeScore() >= 0.6);
        System.out.println(result);
    }

    @Test
    @DisplayName("意图不匹配的评测")
    void testIntentMismatch() {
        AgentEvaluationResult result = new AgentEvaluationResult.Builder("T-002")
                .agentName("order")
                .input("推荐一部电影")
                .expectedIntent("movie_recommend")
                .actualIntent("order_query")
                .expectedTools(List.of("recommendMovie"))
                .actualToolsCalled(List.of("queryOrder"))
                .actualToolCallCount(1)
                .actualIterations(3)
                .actualLatencyMs(2000)
                .expectedKeywords(List.of("电影"))
                .actualResponse("请提供您的订单号。")
                .build();

        assertFalse(result.passed(), "意图不匹配应不通过");
        assertEquals(0.0, result.getIntentMatchScore(), 0.001);
        assertEquals(0.0, result.getToolSelectionAccuracy(), 0.001);
        assertEquals(0.0, result.getResponseQualityScore(), 0.001);
    }

    @Test
    @DisplayName("发生错误的评测")
    void testErrorAgentEval() {
        AgentEvaluationResult result = new AgentEvaluationResult.Builder("T-003")
                .agentName("general")
                .input("计算 1+1")
                .hasError(true)
                .errorMessage("模型调用超时")
                .actualIterations(5)
                .actualLatencyMs(60000)
                .actualResponse("")
                .build();

        assertFalse(result.passed(), "有错误应不通过");
        assertTrue(result.getCompositeScore() <= 0.3, "有错误综合分应 <= 0.3");
    }

    @Test
    @DisplayName("工具选择部分正确")
    void testPartialToolAccuracy() {
        AgentEvaluationResult result = new AgentEvaluationResult.Builder("T-004")
                .agentName("product")
                .input("查询手机价格和库存")
                .expectedTools(List.of("queryProductInfo", "checkStock"))
                .actualToolsCalled(List.of("queryProductInfo"))
                .actualToolCallCount(1)
                .actualIterations(2)
                .actualLatencyMs(800)
                .expectedKeywords(List.of("手机", "价格"))
                .actualResponse("该手机价格为3999元。")
                .build();

        // 工具选择准确率 = 1/2 = 0.5（只调用了 queryProductInfo，缺少 checkStock）
        assertEquals(0.5, result.getToolSelectionAccuracy(), 0.001);
    }

    @Test
    @DisplayName("批量报告生成")
    void testBatchReport() {
        var r1 = new AgentEvaluationResult.Builder("T-001")
                .agentName("order").input("查订单").expectedIntent("order_query")
                .actualIntent("order_query").expectedKeywords(List.of("订单"))
                .actualResponse("您的订单已发货。").actualIterations(2)
                .actualLatencyMs(1000).build();

        var r2 = new AgentEvaluationResult.Builder("T-002")
                .agentName("product").input("查价格").expectedIntent("price_query")
                .actualIntent("other").expectedKeywords(List.of("价格"))
                .actualResponse("无结果。").actualIterations(3)
                .actualLatencyMs(5000).build();

        String report = AgentEvaluationResult.generateBatchReport(List.of(r1, r2));
        assertTrue(report.contains("Agent 批量评测报告"));
        assertTrue(report.contains("order"));
        assertTrue(report.contains("product"));
        System.out.println(report);
    }
}
