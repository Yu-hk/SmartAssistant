/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合规评分器（REQ-3 规则 grader）——对照规则集对文本做命中检测与改写建议。
 * <p>
 * 设计对齐架构「复用 EvaluationReportService grader 思路」：纯规则匹配（默认），
 * 可选 LLM 判分（{@code llmEnabled} 预留，默认关闭以避免外部依赖与误杀）。
 * 产出 {@link ComplianceResult} 的「原始命中 + 改写建议」，最终处置由
 * {@link ComplianceGuard} 结合全局默认策略决定。
 * </p>
 */
public class ComplianceGrader {

    private static final Logger log = LoggerFactory.getLogger(ComplianceGrader.class);

    private final ComplianceRuleSet ruleSet;
    private final boolean llmEnabled;

    public ComplianceGrader(ComplianceRuleSet ruleSet) {
        this(ruleSet, false);
    }

    public ComplianceGrader(ComplianceRuleSet ruleSet, boolean llmEnabled) {
        this.ruleSet = ruleSet != null ? ruleSet : new ComplianceRuleSet(List.of());
        this.llmEnabled = llmEnabled;
    }

    /**
     * 对文本评分（规则匹配）。
     *
     * @return 未命中返回 {@code pass}；命中返回含改写建议的结果（处置策略待 Guard 决定）
     */
    public ComplianceResult grade(String text) {
        if (text == null || text.isBlank()) {
            return ComplianceResult.pass(text != null ? text : "");
        }
        List<ComplianceRule> matched = ruleSet.match(text);
        if (matched.isEmpty()) {
            return ComplianceResult.pass(text);
        }

        String severity = highestSeverity(matched);
        String rewritten = applyRewrite(text, matched);

        // LLM 增强判分（预留）：当前关闭，不影响主链路；开启时可由子类覆盖 enrich()。
        if (llmEnabled) {
            log.debug("[ComplianceGrader] LLM 判分已启用（当前为规则基线）");
        }

        log.info("[ComplianceGrader] 命中 {} 条规则（severity={}）：{}",
                matched.size(), severity, matchedRuleIds(matched));
        // strategyApplied 由 Guard 决定，这里先给占位；rewritten 携带改写建议
        return ComplianceResult.of(true, matched, severity, "", text, rewritten, rewritten);
    }

    /** 最高严重度 */
    private String highestSeverity(List<ComplianceRule> matched) {
        int max = 0;
        String sev = "LOW";
        for (ComplianceRule r : matched) {
            int rank = r.severityRank();
            if (rank > max) {
                max = rank;
                sev = r.getSeverity();
            }
        }
        return sev;
    }

    /**
     * 按命中规则的 rewrite 改写文本（regex 大小写不敏感替换，全部出现均替换）。
     * <p>仅对 strategy 为 rewrite/block 且 rewrite 非空的规则生效。</p>
     */
    private String applyRewrite(String text, List<ComplianceRule> matched) {
        String result = text;
        for (ComplianceRule rule : matched) {
            if (rule.getRewrite() == null || rule.getRewrite().isBlank()) continue;
            if (!"rewrite".equalsIgnoreCase(rule.getStrategy())
                    && !"block".equalsIgnoreCase(rule.getStrategy())) continue;
            try {
                Pattern p = Pattern.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE);
                result = p.matcher(result)
                        .replaceAll(Matcher.quoteReplacement(rule.getRewrite()));
            } catch (Exception e) {
                log.debug("[ComplianceGrader] 规则 {} 改写失败: {}", rule.getId(), e.getMessage());
            }
        }
        return result;
    }

    private List<String> matchedRuleIds(List<ComplianceRule> matched) {
        return matched.stream().map(ComplianceRule::getId).toList();
    }
}
