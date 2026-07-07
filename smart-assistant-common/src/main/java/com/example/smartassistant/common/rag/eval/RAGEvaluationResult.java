/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 综合评测结果 — 汇总检索指标 + 生成质量 + 幻觉检测。
 * <p>
 * 对应面试题 Q03 "RAG 评测维度"，整合检索侧（Recall/MRR/nDCG）
 * 和生成侧（Faithfulness/幻觉率/上下文相关性）的全维度评估结果。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-06
 */
public class RAGEvaluationResult {

    /** 被评测的查询语句 */
    private final String query;

    /** 检索指标列表（Recall@K, Precision@K, MRR, nDCG@K 等） */
    private final List<RetrievalMetrics.MetricResult> retrievalMetrics;

    /** Faithfulness 校验结果 */
    private final ContextFaithfulnessChecker.FaithfulnessResult faithfulnessResult;

    /** 幻觉检测结果 */
    private final HallucinationDetector.HallucinationResult hallucinationResult;

    /** 总延迟（毫秒） */
    private final long totalLatencyMs;

    /** 评测时间戳 */
    private final long timestamp;

    public RAGEvaluationResult(
            String query,
            List<RetrievalMetrics.MetricResult> retrievalMetrics,
            ContextFaithfulnessChecker.FaithfulnessResult faithfulnessResult,
            HallucinationDetector.HallucinationResult hallucinationResult,
            long totalLatencyMs) {
        this.query = query;
        this.retrievalMetrics = retrievalMetrics != null ? retrievalMetrics : List.of();
        this.faithfulnessResult = faithfulnessResult;
        this.hallucinationResult = hallucinationResult;
        this.totalLatencyMs = totalLatencyMs;
        this.timestamp = System.currentTimeMillis();
    }

    // ==================== Getters ====================

    public String getQuery() { return query; }
    public List<RetrievalMetrics.MetricResult> getRetrievalMetrics() { return retrievalMetrics; }
    public ContextFaithfulnessChecker.FaithfulnessResult getFaithfulnessResult() { return faithfulnessResult; }
    public HallucinationDetector.HallucinationResult getHallucinationResult() { return hallucinationResult; }
    public long getTotalLatencyMs() { return totalLatencyMs; }
    public long getTimestamp() { return timestamp; }

    /**
     * 综合评分（0.0 ~ 1.0）。
     * <p>
     * 加权组合：检索指标(40%) + 忠实性(30%) + 幻觉率反向(30%)。
     * </p>
     */
    public double compositeScore() {
        double retrievalScore = retrievalMetrics.stream()
                .filter(m -> "nDCG".equals(m.metricName()) && m.k() > 0)
                .mapToDouble(RetrievalMetrics.MetricResult::value)
                .average()
                .orElse(retrievalMetrics.stream()
                        .filter(m -> "MRR".equals(m.metricName()))
                        .mapToDouble(RetrievalMetrics.MetricResult::value)
                        .average()
                        .orElse(0.0));

        double faithfulnessScore = faithfulnessResult != null
                ? faithfulnessResult.score()
                : 0.0;

        double hallucinationPenalty = hallucinationResult != null
                ? (1.0 - hallucinationResult.hallucinationRate())
                : 1.0;

        return 0.4 * retrievalScore + 0.3 * faithfulnessScore + 0.3 * hallucinationPenalty;
    }

    /**
     * 整体是否通过评估（综合评分 >= 0.7）。
     */
    public boolean passed() {
        return compositeScore() >= 0.7;
    }

    @Override
    public String toString() {
        double score = compositeScore();
        StringBuilder sb = new StringBuilder();
        sb.append("=== RAG 评测结果 ===\n");
        sb.append("查询: ").append(query).append("\n");
        sb.append("综合评分: ").append(String.format("%.2f", score))
                .append(score >= 0.7 ? " ✅" : " ❌").append("\n\n");
        sb.append("--- 检索指标 ---\n");
        for (RetrievalMetrics.MetricResult m : retrievalMetrics) {
            sb.append("  ").append(m).append("\n");
        }
        sb.append("\n--- 忠实性 ---\n");
        if (faithfulnessResult != null) {
            sb.append("  ").append(faithfulnessResult.detail()).append("\n");
        }
        sb.append("\n--- 幻觉检测 ---\n");
        if (hallucinationResult != null) {
            sb.append("  幻觉率: ").append(String.format("%.2f", hallucinationResult.hallucinationRate())).append("\n");
            sb.append("  ").append(hallucinationResult.detail()).append("\n");
            if (!hallucinationResult.claims().isEmpty()) {
                sb.append("  细节:\n");
                for (var claim : hallucinationResult.claims()) {
                    sb.append("    - [").append(claim.type()).append("] ")
                            .append(claim.description()).append("\n");
                }
            }
        }
        sb.append("\n延迟: ").append(totalLatencyMs).append("ms\n");
        return sb.toString();
    }

    // ==================== 批量报告 ====================

    /**
     * 从多个评测结果生成汇总报告。
     */
    public static String generateBatchReport(List<RAGEvaluationResult> results) {
        if (results == null || results.isEmpty()) {
            return "无评测数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════\n");
        sb.append("       RAG 批量评测报告\n");
        sb.append("═══════════════════════════════════════\n\n");

        int total = results.size();
        long passed = results.stream().filter(RAGEvaluationResult::passed).count();
        sb.append(String.format("总评测数: %d | 通过: %d | 失败: %d | 通过率: %.1f%%\n\n",
                total, passed, total - passed, total > 0 ? passed * 100.0 / total : 0));

        // 平均指标
        double avgScore = results.stream().mapToDouble(RAGEvaluationResult::compositeScore).average().orElse(0);
        double avgLatency = results.stream().mapToLong(RAGEvaluationResult::getTotalLatencyMs).average().orElse(0);
        sb.append(String.format("平均综合评分: %.4f\n", avgScore));
        sb.append(String.format("平均延迟: %.0fms\n", avgLatency));
        sb.append("\n");

        // 按指标汇总
        sb.append("--- 各维度平均分 ---\n");
        double avgFaithfulness = results.stream()
                .filter(r -> r.getFaithfulnessResult() != null)
                .mapToDouble(r -> r.getFaithfulnessResult().score())
                .average()
                .orElse(0);
        double avgHallucinationRate = results.stream()
                .filter(r -> r.getHallucinationResult() != null)
                .mapToDouble(r -> r.getHallucinationResult().hallucinationRate())
                .average()
                .orElse(0);
        sb.append(String.format("  检索质量 (nDCG):      %.4f\n",
                results.stream()
                        .flatMap(r -> r.getRetrievalMetrics().stream())
                        .filter(m -> "nDCG".equals(m.metricName()))
                        .mapToDouble(RetrievalMetrics.MetricResult::value)
                        .average().orElse(0)));
        sb.append(String.format("  忠实性 (Faithfulness): %.4f\n", avgFaithfulness));
        sb.append(String.format("  幻觉率:               %.4f\n", avgHallucinationRate));
        sb.append("\n");

        // 失败详情
        sb.append("--- 失败详情 ---\n");
        boolean hasFailures = false;
        for (RAGEvaluationResult result : results) {
            if (!result.passed()) {
                hasFailures = true;
                sb.append(String.format("  ❌ %s (%.2f): 检索=%d指标 | 忠实=%.2f | 幻觉=%.2f\n",
                        result.query, result.compositeScore(),
                        result.getRetrievalMetrics().size(),
                        result.getFaithfulnessResult() != null ? result.getFaithfulnessResult().score() : 0,
                        result.getHallucinationResult() != null ? result.getHallucinationResult().hallucinationRate() : 0));
            }
        }
        if (!hasFailures) {
            sb.append("  全部通过 ✅\n");
        }

        sb.append("\n═══════════════════════════════════════\n");
        return sb.toString();
    }
}
