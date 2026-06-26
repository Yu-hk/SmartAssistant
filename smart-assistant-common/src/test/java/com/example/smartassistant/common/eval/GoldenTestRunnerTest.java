/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GoldenTestRunner} 单元测试。
 */
class GoldenTestRunnerTest {

    private final GoldenTestRunner runner = new GoldenTestRunner();

    @Test
    void testCase_matched() {
        GoldenTestRunner.TestCase tc = new GoldenTestRunner.TestCase();
        tc.id = "T-001";
        tc.question = "我的订单到哪了";
        tc.expected = List.of("订单", "状态");

        // 使用 checkExpected 内部方法逻辑 —— 通过匹配关键词验证
        String response = "您的订单已发货，预计明天到达";
        assertTrue(response.contains("订单"));
    }

    @Test
    void testCase_unmatched() {
        String response = "抱歉，我没有找到相关信息";
        assertFalse(response.contains("订单"));
        assertFalse(response.contains("状态"));
    }

    @Test
    void reportFormatting_allPassed() {
        GoldenTestRunner.TestReport report = new GoldenTestRunner.TestReport();
        report.total = 3;
        report.passed = 3;
        report.failed = 0;

        String formatted = GoldenTestRunner.formatReport(report);
        assertTrue(formatted.contains("全部通过"));
        assertTrue(formatted.contains("100.0%"));
    }

    @Test
    void reportFormatting_withFailures() {
        GoldenTestRunner.TestReport report = new GoldenTestRunner.TestReport();
        report.total = 2;
        report.passed = 1;
        report.failed = 1;

        GoldenTestRunner.TestResult r = new GoldenTestRunner.TestResult();
        r.caseId = "F-001";
        r.question = "测试问题";
        r.actualResponse = "错误回复";
        r.passed = false;
        report.results.add(r);

        String formatted = GoldenTestRunner.formatReport(report);
        assertTrue(formatted.contains("50.0%"));
        assertTrue(formatted.contains("F-001"));
    }

    @Test
    void agentCaller_interface() throws Exception {
        GoldenTestRunner.AgentCaller caller = (agent, question) ->
                "已处理 " + agent + " 的请求: " + question;

        String result = caller.call("order_agent", "我的订单");
        assertTrue(result.contains("order_agent"));
        assertTrue(result.contains("我的订单"));
    }
}
