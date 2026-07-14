/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.compliance;

import java.util.List;

/**
 * 合规黄金测试集单用例评测结果（REQ-8）——风格对齐 {@code RAGEvaluationResult}（不可变 + getter）。
 *
 * <p>由 {@link ComplianceGoldenSuiteEvaluator#evaluate(List)} 产出，承载单用例的命中/通过结论，
 * 供 {@link com.example.smartassistant.common.eval.GoldenSuiteEvalGate} 聚合进质量门禁。</p>
 */
public class ComplianceEvaluationResult {

    /** 用例 ID（与 ComplianceTestCase.id 对应） */
    private final String caseId;

    /** 是否通过（命中预期 & 改写生效 & 良性未误杀） */
    private final boolean passed;

    /** 实际命中的规则 ID 列表 */
    private final List<String> matchedRuleIds;

    /** 期望命中的规则 ID 列表 */
    private final List<String> expectedRuleIds;

    /** 改写后文本（grader 产出；良性/未命中时为 null 或原文） */
    private final String rewritten;

    /** 结论说明（便于失败排查） */
    private final String message;

    private ComplianceEvaluationResult(String caseId, boolean passed, List<String> matchedRuleIds,
                                        List<String> expectedRuleIds, String rewritten, String message) {
        this.caseId = caseId != null ? caseId : "";
        this.passed = passed;
        this.matchedRuleIds = matchedRuleIds != null ? List.copyOf(matchedRuleIds) : List.of();
        this.expectedRuleIds = expectedRuleIds != null ? List.copyOf(expectedRuleIds) : List.of();
        this.rewritten = rewritten;
        this.message = message != null ? message : "";
    }

    public static ComplianceEvaluationResult of(String caseId, boolean passed, List<String> matchedRuleIds,
                                                List<String> expectedRuleIds, String rewritten, String message) {
        return new ComplianceEvaluationResult(caseId, passed, matchedRuleIds, expectedRuleIds, rewritten, message);
    }

    public String getCaseId() { return caseId; }
    public boolean isPassed() { return passed; }
    public List<String> getMatchedRuleIds() { return matchedRuleIds; }
    public List<String> getExpectedRuleIds() { return expectedRuleIds; }
    public String getRewritten() { return rewritten; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return "ComplianceEvaluationResult(caseId=" + caseId + ", passed=" + passed
                + ", matched=" + matchedRuleIds + ", expected=" + expectedRuleIds
                + ", message=" + message + ")";
    }
}
