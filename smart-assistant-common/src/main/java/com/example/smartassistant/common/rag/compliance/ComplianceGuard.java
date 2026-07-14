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
import java.util.Set;

/**
 * 合规护栏（REQ-3 处置中枢）——消费 {@link ComplianceGrader} 的命中结果，
 * 结合全局默认策略 {@code app.compliance.default-strategy} 决定最终处置，并落审计。
 *
 * <h3>处置决策（对齐架构 §8.4）</h3>
 * <ul>
 *   <li>任何命中规则含 {@code block} → <b>BLOCK</b>：返回安全拒答模板（安全优先，绕过全局默认）；</li>
 *   <li>否则采用全局默认策略（默认 {@code rewrite}）：</li>
 *   <ul>
 *     <li>{@code rewrite} → 用 grader 改写后的文本放行（改写绝对化 / 超承诺表述）；</li>
 *     <li>{@code warn} → 仅告警 + 写审计，原文放行；</li>
 *     <li>{@code block} → 安全模板拒答。</li>
 *   </ul>
 * </ul>
 * <p>
 * 设计取舍：逐规则自带 {@code strategy}（rule-level 优先），但全局默认策略提供系统级兜底；
 * 仅当命中规则显式标注 {@code block}（如「稳赚不赔」「包赚」等明确不安全内容）时强制 BLOCK，
 * 以将「误杀率 ≤ 5%」控制在合理范围（普通绝对化表述走 rewrite，不拒答）。
 * 每次命中均写 {@code compliance_audit_log}（REQ-3 验收⑤）。
 * </p>
 */
public class ComplianceGuard {

    private static final Logger log = LoggerFactory.getLogger(ComplianceGuard.class);

    /** 安全拒答模板（BLOCK 处置输出） */
    public static final String SAFE_TEMPLATE =
            "抱歉，依据合规要求，我无法对相关内容作出承诺或保证。"
            + "如有进一步疑问，请联系人工客服获取准确信息。";

    /** 合法策略集合 */
    private static final Set<String> VALID_STRATEGIES = Set.of("warn", "rewrite", "block");

    private final ComplianceGrader grader;
    private final ComplianceAuditRecorder recorder;
    private final String defaultStrategy;
    private final boolean enabled;

    public ComplianceGuard(ComplianceGrader grader, ComplianceAuditRecorder recorder,
                           String defaultStrategy, boolean enabled) {
        this.grader = grader != null ? grader : new ComplianceGrader(null);
        this.recorder = recorder != null ? recorder : new ComplianceAuditRecorder();
        this.defaultStrategy = normalizeStrategy(defaultStrategy);
        this.enabled = enabled;
    }

    /**
     * 对生成文本做合规校验与处置。
     *
     * @return 含最终应输出文本的 {@link ComplianceResult}
     */
    public ComplianceResult check(String text) {
        if (!enabled) {
            return ComplianceResult.pass(text != null ? text : "");
        }
        if (text == null || text.isBlank()) {
            return ComplianceResult.pass(text != null ? text : "");
        }

        ComplianceResult graded = grader.grade(text);
        if (!graded.isHit()) {
            return graded; // 未命中，原样放行
        }

        // 解析最终处置策略
        String strategy = resolveStrategy(graded.getMatched());
        String severity = graded.getSeverity();
        String original = graded.getOriginal();
        String rewritten = graded.getRewritten();

        ComplianceResult result;
        switch (strategy) {
            case "BLOCK":
                result = ComplianceResult.of(true, graded.getMatched(), severity, "BLOCK",
                        original, rewritten, SAFE_TEMPLATE);
                break;
            case "REWRITE":
                // 改写后文本放行；若 grader 未产生改写（如 warn 升级），则回退原文
                String out = (rewritten != null && !rewritten.equals(original)) ? rewritten : original;
                result = ComplianceResult.of(true, graded.getMatched(), severity, "REWRITE",
                        original, rewritten, out);
                break;
            case "WARN":
            default:
                // 仅告警 + 审计，原文放行
                result = ComplianceResult.of(true, graded.getMatched(), severity, "WARN",
                        original, rewritten, original);
                break;
        }

        // 落审计（每次命中均写）
        String primaryRuleId = primaryRuleId(graded.getMatched());
        recorder.record(primaryRuleId, severity, result.getStrategyApplied(),
                original, result.getOutput());

        log.info("[ComplianceGuard] 处置完成: strategy={}, severity={}, primaryRule={}, tenant from MDC",
                result.getStrategyApplied(), severity, primaryRuleId);
        return result;
    }

    /**
     * 解析最终处置策略：
     * <ul>
     *   <li>命中任意 {@code block} 规则 → BLOCK（安全优先，绕过全局默认）；</li>
     *   <li>否则采用全局默认策略（缺省 rewrite，非法值回退 rewrite）。</li>
     * </ul>
     */
    private String resolveStrategy(List<ComplianceRule> matched) {
        boolean anyBlock = matched.stream()
                .anyMatch(r -> "block".equalsIgnoreCase(r.getStrategy()));
        if (anyBlock) {
            return "BLOCK";
        }
        return defaultStrategy.toUpperCase();
    }

    /** 取最高严重度的规则 ID 作为主规则（审计溯源用） */
    private String primaryRuleId(List<ComplianceRule> matched) {
        ComplianceRule best = null;
        int max = 0;
        for (ComplianceRule r : matched) {
            int rank = r.severityRank();
            if (rank > max) {
                max = rank;
                best = r;
            }
        }
        return best != null ? best.getId() : (matched.isEmpty() ? "" : matched.get(0).getId());
    }

    /** 规范化全局默认策略（缺省 rewrite，非法回退 rewrite） */
    private static String normalizeStrategy(String s) {
        if (s == null || s.isBlank()) return "rewrite";
        String lower = s.trim().toLowerCase();
        return VALID_STRATEGIES.contains(lower) ? lower : "rewrite";
    }
}
