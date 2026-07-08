/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import com.example.smartassistant.common.eval.EvaluationReportService.AgentTestSpec;
import com.example.smartassistant.common.eval.grader.GraderResult;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 人工复核路由 — 按文章「2% 抽检 + 边界升级」策略决定哪些用例需人工金标准。
 *
 * <p>路由规则（任一命中即升级）：</p>
 * <ol>
 *   <li>LLM/规则标记 {@code requiresHumanReview}（低置信）；</li>
 *   <li>分数落在阈值边界 ±0.1（模糊区）；</li>
 *   <li>随机抽样比例 {@code sampleRate}（默认 0.02）。</li>
 * </ol>
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public class HumanReviewRouter {

    /** 判定阈值（与 AgentEvaluationResult.passed 一致）。 */
    public static final double PASS_THRESHOLD = 0.6;
    /** 边界带宽。 */
    public static final double BAND = 0.1;

    private final HumanReviewStore store;
    private final double sampleRate;
    private final Random random;
    private final AtomicLong routedCount = new AtomicLong();

    public HumanReviewRouter(HumanReviewStore store) {
        this(store, 0.02);
    }

    public HumanReviewRouter(HumanReviewStore store, double sampleRate) {
        this.store = store;
        this.sampleRate = Math.max(0, Math.min(1, sampleRate));
        this.random = new Random();
    }

    /** 是否应路由到人工。 */
    public boolean shouldRoute(AgentEvaluationResult result, GraderResult graderResult) {
        if (graderResult != null && graderResult.requiresHumanReview()) return true;
        double s = result.getCompositeScore();
        if (Math.abs(s - PASS_THRESHOLD) <= BAND) return true;        // 边界
        return random.nextDouble() < sampleRate;                       // 2% 抽检
    }

    /** 生成并存储人工复核任务（若需路由）。返回是否路由。 */
    public boolean route(AgentEvaluationResult result, GraderResult graderResult, AgentTestSpec spec) {
        if (!shouldRoute(result, graderResult)) return false;
        String reason = (graderResult != null && graderResult.requiresHumanReview())
                ? "低置信度(LLM/Judge)" : "边界或抽检";
        HumanReviewTask task = HumanReviewTask.of(result, graderResult, spec, reason);
        store.save(task);
        routedCount.incrementAndGet();
        return true;
    }

    public long routedCount() {
        return routedCount.get();
    }
}
