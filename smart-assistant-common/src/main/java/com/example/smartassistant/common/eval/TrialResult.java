/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import java.util.List;

/**
 * 单次测试用例的多轮 Trial 结果 — 对应文章《Agent 评测体系》中「一次评测链路」的 Trial 概念。
 *
 * <p>生产级 Agent 评估不能只看单次运行（LLM 非确定性），需多次独立运行后聚合：</p>
 * <ul>
 *   <li><b>pass@k</b>：k 次独立机会中至少一次成功的概率（文章：十次过八次才是能力）；</li>
 *   <li><b>pass^k</b>：k 次全成功的概率（生产客服/支付关心稳定性，要求每次都成）。</li>
 * </ul>
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public record TrialResult(
        String caseId,
        List<AgentEvaluationResult> trials,
        int passCount,
        int k) {

    public TrialResult {
        if (trials == null) trials = List.of();
        if (k <= 0) k = trials.size();
    }

    /** Trial 总数（即运行次数 n）。 */
    public int trialCount() {
        return trials.size();
    }

    /** 观测通过率 = passCount / trialCount。 */
    public double observedPassRate() {
        return trialCount() == 0 ? 0.0 : (double) passCount / trialCount();
    }
}
