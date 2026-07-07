/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索链路追溯记录 — 记录 RAG 检索全链路每个步骤的详细信息。
 * <p>
 * 结构：{@code query → queryVariants → 各路召回(step) → RRF融合(fused) → 最终入Prompt(final)}
 * 存入 Redis 供线上调试和失败样本分析。
 * </p>
 */
public class RetrievalTrace {

    /** 请求 ID */
    private final String requestId;

    /** 原始用户问题 */
    private final String originalQuery;

    /** 改写/扩展后的查询变体（Multi-Query） */
    private final List<String> queryVariants;

    /** 各路召回结果 */
    private final List<RetrievalStep> steps;

    /** RRF 融合后结果 */
    private final List<FusedResult> fusedResults;

    /** 最终入 Prompt 的上下文片段 */
    private final List<String> finalContext;

    /** 冲突消解后的分数明细（每个 finalContext 项对应一个，JSON 友好的 flat record） */
    private final List<ScoreBreakdown> scoreBreakdowns;

    /** 总耗时（ms） */
    private long totalDurationMs;

    /** 检索是否命中 */
    private boolean hit;

    public RetrievalTrace(String requestId, String originalQuery) {
        this.requestId = requestId;
        this.originalQuery = originalQuery;
        this.queryVariants = new ArrayList<>();
        this.steps = new ArrayList<>();
        this.fusedResults = new ArrayList<>();
        this.finalContext = new ArrayList<>();
        this.scoreBreakdowns = new ArrayList<>();
    }

    // ==================== 构建器方法 ====================

    public RetrievalTrace addVariant(String variant) {
        this.queryVariants.add(variant);
        return this;
    }

    public RetrievalTrace addStep(String pathName, String query, int rank,
                                   String docId, String title, double score) {
        this.steps.add(new RetrievalStep(pathName, query, rank, docId, title, score));
        return this;
    }

    public RetrievalTrace addFused(String docId, String title, double rrfScore, int rank) {
        this.fusedResults.add(new FusedResult(docId, title, rrfScore, rank));
        return this;
    }

    public RetrievalTrace addFinalContext(String context) {
        this.finalContext.add(context);
        return this;
    }

    /** 记录一条冲突消解后的分数明细 */
    public RetrievalTrace addScoreBreakdown(double baseScore, double authorityFactor,
                                             double conflictPenalty, double finalScore) {
        this.scoreBreakdowns.add(new ScoreBreakdown(baseScore, authorityFactor, conflictPenalty, finalScore));
        return this;
    }

    public RetrievalTrace durationMs(long ms) {
        this.totalDurationMs = ms;
        return this;
    }

    public RetrievalTrace hit(boolean hit) {
        this.hit = hit;
        return this;
    }

    // ==================== 查询方法 ====================

    public String getRequestId() { return requestId; }
    public String getOriginalQuery() { return originalQuery; }
    public List<String> getQueryVariants() { return queryVariants; }
    public List<RetrievalStep> getSteps() { return steps; }
    public List<FusedResult> getFusedResults() { return fusedResults; }
    public List<String> getFinalContext() { return finalContext; }
    public List<ScoreBreakdown> getScoreBreakdowns() { return scoreBreakdowns; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public boolean isHit() { return hit; }

    /** 汇总统计（调试输出用） */
    public String toSummary() {
        return String.format(
                "检索链路: query='%s', variants=%d, steps=%d, fused=%d, scoreBreakdowns=%d, hit=%b, duration=%dms",
                originalQuery, queryVariants.size(), steps.size(), fusedResults.size(), scoreBreakdowns.size(), hit, totalDurationMs
        );
    }

    // ==================== 内部记录类 ====================

    /** 单路检索步 */
    public record RetrievalStep(String pathName, String query, int rank,
                                String docId, String title, double score) {}

    /** RRF 融合结果 */
    public record FusedResult(String docId, String title, double rrfScore, int rank) {}

    /** 冲突消解后分数明细（JSON 友好，与 CrossDocumentConflictResolver.ScoreBreakdown 同构） */
    public record ScoreBreakdown(double baseScore, double authorityFactor,
                                  double conflictPenalty, double finalScore) {}
}
