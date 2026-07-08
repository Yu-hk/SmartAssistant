/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval.grader;

import com.example.smartassistant.common.eval.AgentEvaluationResult;
import com.example.smartassistant.common.eval.EvaluationReportService.AgentTestSpec;

/**
 * 规则打分器 — 复用 {@link AgentEvaluationResult} 已计算的各维度分（意图/工具/质量/效率），
 * 输出确定性的二元或部分得分。作为瀑布流第一环（快、便宜、可复现）。
 *
 * <p>partial credit 规则：当工具选择准确率落在 (0,1) 之间（部分命中预期工具）时标记 partial=true，
 * 避免「全有全无」的刚性误判。错误但综合分落在 (0.3, 0.6) 的模糊区标记为需人工升级。</p>
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public class RuleGrader implements Grader {

    public static final double PASS_THRESHOLD = 0.6;

    @Override
    public GraderResult grade(AgentEvaluationResult result, AgentTestSpec spec) {
        double composite = result.getCompositeScore();
        boolean partial = isPartial(result);
        String rationale = buildRationale(result);
        // 规则打分确定性高；边界区间（0.3~0.6 且有错误）视为模糊，交由上层升级人工
        boolean ambiguous = result.isHasError() && composite > 0.3 && composite < PASS_THRESHOLD;
        return new GraderResult(composite, partial, rationale, "RULE",
                ambiguous ? 0.5 : 0.95, ambiguous);
    }

    private boolean isPartial(AgentEvaluationResult r) {
        double tool = r.getToolSelectionAccuracy();
        double quality = r.getResponseQualityScore();
        return (tool > 0.0 && tool < 1.0) || (quality > 0.0 && quality < 1.0);
    }

    private String buildRationale(AgentEvaluationResult r) {
        return String.format("意图=%.2f 工具=%.2f 质量=%.2f 效率=%.2f 综合=%.2f%s",
                r.getIntentMatchScore(), r.getToolSelectionAccuracy(),
                r.getResponseQualityScore(), r.getEfficiencyScore(), r.getCompositeScore(),
                r.isHasError() ? " [ERROR]" : "");
    }
}
