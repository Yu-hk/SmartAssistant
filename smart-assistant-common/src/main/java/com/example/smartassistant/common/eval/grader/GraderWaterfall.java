/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval.grader;

import com.example.smartassistant.common.eval.AgentEvaluationResult;
import com.example.smartassistant.common.eval.EvaluationReportService.AgentTestSpec;

import java.util.List;

/**
 * 打分瀑布流 — 按 规则 → LLM 顺序，低置信/模糊时逐级升级（文章 §6 Grader 三件套）。
 *
 * <p>规则层确定性高、成本低，优先使用；仅当规则标记为模糊（置信度不足）时才升级到 LLM，
 * 既控制 LLM 调用成本，又保证语义质量不被规则刚性误杀。</p>
 *
 * <p>人工路由由上层编排器（{@code EvalPipeline}）统一负责，本组件保持纯打分职责。</p>
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public class GraderWaterfall implements Grader {

    private final List<Grader> graders;

    public GraderWaterfall(List<Grader> graders) {
        this.graders = graders;
    }

    @Override
    public GraderResult grade(AgentEvaluationResult result, AgentTestSpec spec) {
        GraderResult last = null;
        for (Grader g : graders) {
            GraderResult r = g.grade(result, spec);
            last = r;
            // 高置信且无需人工 → 直接采用，停止升级
            if (!r.requiresHumanReview() && r.confidence() >= 0.9) {
                return r;
            }
        }
        return last != null ? last : new GraderResult(0, false, "无可用打分器", "NONE", 0, true);
    }
}
