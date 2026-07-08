/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval.grader;

/**
 * 单次打分结果 — 支持部分得分（partial credit）与人工复核标记。
 *
 * <p>对应文章《Agent 评测体系》§6 的 Grader 三件套与「partial credit」原则：
 * 规则太刚性会把「钻政策漏洞但实质正确」的 Opus 4.5 错判失败，需允许部分得分。</p>
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public record GraderResult(
        double score,               // 0.0 ~ 1.0
        boolean partial,            // 是否部分得分（非 0/1 二元）
        String rationale,
        String graderType,          // "RULE" / "LLM" / "HUMAN"
        double confidence,          // 0.0 ~ 1.0 打分置信度
        boolean requiresHumanReview) {

    public boolean passed(double threshold) {
        return score >= threshold;
    }
}
