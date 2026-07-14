/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.compliance;

import java.util.List;

/**
 * 合规校验结果（REQ-3）——承载命中规则、严重度、处置策略与最终输出文本。
 * <p>
 * 由 {@link ComplianceGuard#check(String)} 返回，供 {@code PostGenerationComplianceAdvisor}
 * 决定对用户输出原文 / 改写后文本 / 安全拒答模板。
 * </p>
 */
public class ComplianceResult {

    /** 是否命中任意规则 */
    private final boolean hit;

    /** 命中的规则列表 */
    private final List<ComplianceRule> matched;

    /** 最高严重度（HIGH / MEDIUM / LOW） */
    private final String severity;

    /** 实际应用的处置策略：PASS / REWRITE / BLOCK / WARN */
    private final String strategyApplied;

    /** 原始文本 */
    private final String original;

    /** 改写建议（grader 产出，可能为空） */
    private final String rewritten;

    /** 最终应输出给用户的文本（改写后 / 拒答模板 / 原文） */
    private final String output;

    private ComplianceResult(boolean hit, List<ComplianceRule> matched, String severity,
                             String strategyApplied, String original, String rewritten, String output) {
        this.hit = hit;
        this.matched = matched != null ? List.copyOf(matched) : List.of();
        this.severity = severity != null ? severity : "";
        this.strategyApplied = strategyApplied != null ? strategyApplied : "PASS";
        this.original = original != null ? original : "";
        this.rewritten = rewritten;
        this.output = output != null ? output : this.original;
    }

    public static ComplianceResult pass(String original) {
        return new ComplianceResult(false, List.of(), "", "PASS", original, null, original);
    }

    public static ComplianceResult of(boolean hit, List<ComplianceRule> matched, String severity,
                                     String strategyApplied, String original, String rewritten, String output) {
        return new ComplianceResult(hit, matched, severity, strategyApplied, original, rewritten, output);
    }

    public boolean isHit() { return hit; }
    public List<ComplianceRule> getMatched() { return matched; }
    public String getSeverity() { return severity; }
    public String getStrategyApplied() { return strategyApplied; }
    public String getOriginal() { return original; }
    public String getRewritten() { return rewritten; }

    /** 最终应输出文本（改写/拒答/原文） */
    public String getOutput() { return output; }

    /** 命中的规则 ID 列表（审计用） */
    public List<String> matchedRuleIds() {
        return matched.stream().map(ComplianceRule::getId).toList();
    }

    @Override
    public String toString() {
        return "ComplianceResult(hit=" + hit + ", severity=" + severity
                + ", strategy=" + strategyApplied + ", matched=" + matched.size() + ")";
    }
}
