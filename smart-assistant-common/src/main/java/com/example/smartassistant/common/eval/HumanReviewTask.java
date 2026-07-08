/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import com.example.smartassistant.common.eval.EvaluationReportService.AgentTestSpec;
import com.example.smartassistant.common.eval.grader.GraderResult;

import java.time.Instant;

/**
 * 人工复核任务 — 瀑布流末环的产物（文章 §6 「人工路由」+「2% 抽检」）。
 *
 * <p>当规则/LLM 置信度不足或分数处于阈值边界时，自动生成复核任务存入队列，
 * 由人工给出金标准判定，反哺 LLM-Judge 校准集与根因分析。</p>
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public record HumanReviewTask(
        String taskId,
        String caseId,
        String agentName,
        String input,
        String actualResponse,
        double ruleScore,
        double llmScore,
        String reason,
        Instant createdAt,
        Status status,
        String decisionNote) {

    public enum Status { PENDING, APPROVED, REJECTED }

    public static HumanReviewTask of(AgentEvaluationResult r, GraderResult gr,
                                      AgentTestSpec spec, String reason) {
        return new HumanReviewTask(
                "HR-" + r.getCaseId() + "-" + Instant.now().toEpochMilli(),
                r.getCaseId(), r.getAgentName(), spec.input, r.getActualResponse(),
                r.getCompositeScore(), gr != null ? gr.score() : r.getCompositeScore(),
                reason, Instant.now(), Status.PENDING, "");
    }
}
