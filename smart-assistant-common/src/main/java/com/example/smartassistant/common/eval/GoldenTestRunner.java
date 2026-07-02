/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 黄金测试集运行器 — 读取 YAML 测试集，调用 Agent 并评分。
 *
 * <p>使用方式：</p>
 * <ol>
 *   <li>在 {@code docs/eval/} 下创建 YAML 测试集文件</li>
 *   <li>确保各 Agent 服务已启动</li>
 *   <li>运行 {@link #runAll(Path, AgentCaller)} 或通过 {@link #main(String[])} 独立执行</li>
 * </ol>
 *
 * <p>输出格式：</p>
 * <pre>
 * === 测试报告 ===
 * 总计: 14 | 通过: 12 | 失败: 2 | 通过率: 85.7%
 *
 * 按 Agent:
 *   order:   5/6  (83.3%)
 *   product: 3/3  (100%)
 *   general: 4/5  (80.0%)
 *
 * 失败详情:
 *   O-003 [退款] → 预期含"退款"，实际回复中未找到
 *   G-002 [汇率] → 预期含"人民币"，实际回复中未找到
 * </pre>
 */
public class GoldenTestRunner {

    private static final Logger log = LoggerFactory.getLogger(GoldenTestRunner.class);

    private final ObjectMapper jsonMapper;

    public GoldenTestRunner() {
        this.jsonMapper = new ObjectMapper();
    }

    /**
     * 从 YAML 文件加载测试用例。
     */
    public List<TestCase> load(Path jsonPath) throws IOException {
        Map<String, Object> root = jsonMapper.readValue(jsonPath.toFile(),
                new TypeReference<>() {
                });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) root.get("test_cases");
        List<TestCase> result = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            TestCase tc = new TestCase();
            tc.id = (String) c.get("id");
            tc.intent = (String) c.get("intent");
            tc.agent = (String) c.get("agent");
            tc.question = (String) c.get("question");
            tc.expected = c.get("expected") instanceof List ? (List<String>) c.get("expected") : List.of();
            tc.priority = (String) c.get("priority");
            result.add(tc);
        }
        return result;
    }

    /**
     * 运行全部测试用例并生成报告。
     *
     * @param cases   测试用例列表
     * @param caller  Agent 调用接口（需自行实现，如通过 HTTP 调用）
     * @return 测试报告文本
     */
    public TestReport runAll(List<TestCase> cases, AgentCaller caller) {
        TestReport report = new TestReport();
        report.total = cases.size();

        for (TestCase tc : cases) {
            TestResult tr = new TestResult();
            tr.caseId = tc.id;
            tr.question = tc.question;

            try {
                String response = caller.call(tc.agent, tc.question);
                tr.actualResponse = response != null ? response : "";
                tr.passed = checkExpected(tc.expected, tr.actualResponse);
            } catch (Exception e) {
                tr.actualResponse = "ERROR: " + e.getMessage();
                tr.passed = false;
                tr.error = e.getMessage();
            }

            if (tr.passed) report.passed++;
            else report.failed++;
            report.results.add(tr);
        }

        return report;
    }

    /** 检查回复中是否包含所有期望关键词 */
    private static boolean checkExpected(List<String> expected, String response) {
        if (expected == null || expected.isEmpty()) return true;
        String r = response.toLowerCase();
        for (String exp : expected) {
            if (!r.contains(exp.toLowerCase())) return false;
        }
        return true;
    }

    /** 格式化测试报告 */
    public static String formatReport(TestReport report) {
        double rate = report.total > 0 ? (report.passed * 100.0 / report.total) : 0;
        StringBuilder sb = new StringBuilder();
        sb.append("=== 测试报告 ===\n\n");
        sb.append(String.format("总计: %d | 通过: %d | 失败: %d | 通过率: %.1f%%\n\n",
                report.total, report.passed, report.failed, rate));

        sb.append("失败详情:\n");
        for (TestResult r : report.results) {
            if (!r.passed) {
                sb.append(String.format("  %s → 预期含 %s，实际: %s\n",
                        r.caseId, r.question, truncate(r.actualResponse, 80)));
            }
        }

        if (report.failed == 0) {
            sb.append("  无 — 全部通过 ✅\n");
        }

        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ==================== 内部数据类型 ====================

    /** 测试用例 */
    public static class TestCase {
        public String id;
        public String intent;
        public String agent;
        public String question;
        public List<String> expected;
        public String priority;
    }

    /** 单个测试结果 */
    public static class TestResult {
        public String caseId;
        public String question;
        public String actualResponse;
        public boolean passed;
        public String error;
    }

    /** 测试报告 */
    public static class TestReport {
        public int total;
        public int passed;
        public int failed;
        public List<TestResult> results = new ArrayList<>();
    }

    /** Agent 调用接口（由使用者实现，如 HTTP 调用） */
    @FunctionalInterface
    public interface AgentCaller {
        String call(String agentName, String question) throws Exception;
    }

    // ==================== 独立运行入口 ====================

    public static void main(String[] args) throws Exception {
        String testPath = args.length > 0 ? args[0] : "docs/eval/sample-test-set.json";
        GoldenTestRunner runner = new GoldenTestRunner();
        List<TestCase> cases = runner.load(Path.of(testPath));
        System.out.println("已加载 " + cases.size() + " 个测试用例");

        // 如果是独立运行，需要提供 AgentCaller 实现
        // 示例：通过 HTTP 调用本地 Consumer 端点
        AgentCaller caller = (agent, question) -> {
            // 实际使用时替换为 HTTP 调用
            return "[模拟响应] 已处理 " + agent + " 的请求: " + question;
        };

        TestReport report = runner.runAll(cases, caller);
        System.out.println(formatReport(report));
    }
}
