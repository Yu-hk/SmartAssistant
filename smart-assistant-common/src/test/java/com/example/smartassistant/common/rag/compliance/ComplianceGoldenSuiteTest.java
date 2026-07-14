/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.compliance;

import com.example.smartassistant.common.eval.EvalGate;
import com.example.smartassistant.common.eval.GoldenSuiteEvalGate;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合规规则 GoldenSuite 回归测试（REQ-8）——纯内存、不依赖 LLM / PG / Redis。
 *
 * <p>作为 CI 回归入口：任一条规则正则失效 / 改写不生效即测试失败。覆盖三类断言：</p>
 * <ul>
 *   <li>触发句应命中期望规则；</li>
 *   <li>良性反例应 pass（无误杀）；</li>
 *   <li>rewrite 改写应生效（不再含原 forbidden 片段）；</li>
 *   <li>GoldenSuiteEvalGate 加载 complianceTests 段后门禁 passed 且 compliance.passRate=1.0；</li>
 *   <li>规则正则失效时门禁应判定失败（回归拦截生效）。</li>
 * </ul>
 */
class ComplianceGoldenSuiteTest {

    private final ComplianceRuleSet rules = ComplianceRuleSet.fromClasspath();
    private final ComplianceGoldenSuiteEvaluator evaluator = new ComplianceGoldenSuiteEvaluator(rules);

    @Test
    void triggerSentencesMustHitExpectedRules() {
        assertHit("我保证一定能帮你订到房间", List.of("C003"));
        assertHit("这个价格永久有效", List.of("C009"));
        assertHit("具体赔付按相关规定执行", List.of("C026"));
        assertHit("这款产品稳赚不赔保本高收益", List.of("C028", "C030"));
        assertHit("吃了这个包治百病", List.of("C021"));
        assertHit("我们绝不泄露您的任何信息", List.of("C023"));
    }

    private void assertHit(String text, List<String> expectedRuleIds) {
        ComplianceResult r = new ComplianceGrader(rules).grade(text);
        assertTrue(r.isHit(), "应命中规则: " + text);
        for (String id : expectedRuleIds) {
            assertTrue(r.matchedRuleIds().contains(id),
                    "应命中规则 " + id + "（实际命中 " + r.matchedRuleIds() + "）: " + text);
        }
    }

    @Test
    void benignSentencesMustPassThroughWithoutFalsePositive() {
        for (String text : List.of(
                "请提供订单号以便查询",
                "标准配送约3-5个工作日",
                "已为您筛选符合条件的商品",
                "感谢您的咨询，祝您生活愉快")) {
            ComplianceResult r = new ComplianceGrader(rules).grade(text);
            assertFalse(r.isHit(), "良性文本不应命中（误杀）: " + text);
        }
    }

    @Test
    void rewriteShouldTakeEffectForRewriteStrategyRules() {
        ComplianceResult r = new ComplianceGrader(rules).grade("我保证一定能帮你订到房间");
        assertTrue(r.isHit());
        assertNotNull(r.getRewritten());
        assertFalse(r.getRewritten().contains("一定能"), "C003 改写后不应仍含 forbidden 片段『一定能』");
        assertTrue(r.getRewritten().contains("通常能"), "C003 应改写为『通常能』");

        ComplianceResult r2 = new ComplianceGrader(rules).grade("吃了这个包治百病");
        assertNotNull(r2.getRewritten());
        assertFalse(r2.getRewritten().contains("包治"), "C021 改写后不应仍含 forbidden 片段『包治』");
    }

    @Test
    void goldenSuiteEvaluatorAllPassFromClasspathSuite() {
        List<ComplianceTestCase> cases = ComplianceGoldenSuiteEvaluator.loadCases("/eval-test-suite.json");
        assertFalse(cases.isEmpty(), "应加载到 complianceTests 用例");

        List<ComplianceEvaluationResult> results = evaluator.evaluate(cases);
        assertEquals(cases.size(), results.size(), "评测结果数应与用例数一致");

        for (ComplianceEvaluationResult res : results) {
            assertTrue(res.isPassed(),
                    "合规用例应全部通过: " + res.getCaseId() + " -> " + res.getMessage());
        }
    }

    @Test
    void gateIncludesComplianceAndMustPass() {
        Path reportDir = Paths.get("target/eval-reports-compliance");
        Path baselinePath = Paths.get("src/test/resources/eval-baseline.json");
        boolean updateBaseline = !Files.exists(baselinePath);

        EvalGate.GateResult result = new GoldenSuiteEvalGate().run(
                "/eval-test-suite.json",
                "/eval-gate-config.json",
                reportDir,
                baselinePath,
                updateBaseline);

        System.out.println("=== Compliance Gate 结论: " + (result.passed() ? "通过" : "未通过") + " ===");
        result.metrics().forEach((k, v) -> System.out.printf("  %s = %.4f%n", k, v));
        if (!result.violations().isEmpty()) {
            System.out.println("违规项:");
            result.violations().forEach(v -> System.out.println("  - " + v));
        }

        assertTrue(result.passed(),
                "GoldenSuiteEvalGate 门禁应通过（含合规回归）：" + String.join("; ", result.violations()));
        assertTrue(result.metrics().containsKey("compliance.passRate"),
                "门禁指标应含 compliance.passRate");
        assertEquals(1.0, result.metrics().get("compliance.passRate"), 1e-6,
                "compliance.passRate 应为 1.0（全部合规用例通过）");
    }

    @Test
    void regressionCatchesBrokenRegex() {
        // 模拟 C003 正则失效：用永远不匹配的 pattern，触发句应判定失败
        ComplianceRule broken = new ComplianceRule("C003", "ZZZ_NOMATCH_ZZZ", "LOW", "rewrite", "通常能", "绝对化");
        ComplianceRuleSet brokenSet = new ComplianceRuleSet(List.of(broken));
        ComplianceGoldenSuiteEvaluator brokenEval = new ComplianceGoldenSuiteEvaluator(brokenSet);

        ComplianceTestCase tc = new ComplianceTestCase(
                "CMP-001", "我保证一定能帮你订到房间", true, List.of("C003"));
        ComplianceEvaluationResult res = brokenEval.evaluate(List.of(tc)).get(0);

        assertFalse(res.isPassed(), "正则失效时应判定失败（回归门禁生效）");
        assertTrue(res.getMessage().contains("缺少期望规则") || res.getMessage().contains("未命中"),
                "失败原因应说明未命中期望规则: " + res.getMessage());
    }
}
