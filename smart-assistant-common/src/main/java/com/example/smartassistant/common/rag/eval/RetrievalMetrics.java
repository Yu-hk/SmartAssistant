/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.eval;

import java.util.*;

/**
 * 检索指标计算工具 — Recall@K / Precision@K / MRR / nDCG。
 * <p>
 * 对应面试题 Q03 "RAG 评测维度"，Q10 "为什么需要 Rerank" 中
 * 对检索侧指标的精确定义和工程化计算。
 * </p>
 *
 * <p>用于离线评测黄金测试集，量化 RAG Pipeline 检索质量：</p>
 * <ul>
 *   <li><b>Recall@K</b>：前 K 个结果中召回了多少相关文档</li>
 *   <li><b>Precision@K</b>：前 K 个结果中有多少是相关的</li>
 *   <li><b>MRR</b>：第一个相关结果的倒数排名均值</li>
 *   <li><b>nDCG@K</b>：归一化折损累计增益，考虑排序位置</li>
 * </ul>
 *
 * @author Yu-hk
 * @since 2026-07-06
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {}

    /**
     * 计算 Recall@K。
     *
     * @param relevantIds 全部相关文档 ID 集合
     * @param retrievedIds 检索返回的文档 ID 列表（顺序敏感，前 K 个用于计算）
     * @param k           截断位置
     * @return Recall@K 值（0.0 ~ 1.0）
     */
    public static double recallAtK(Set<String> relevantIds, List<String> retrievedIds, int k) {
        if (relevantIds == null || relevantIds.isEmpty() || retrievedIds == null || retrievedIds.isEmpty()) {
            return 0.0;
        }
        int effectiveK = Math.min(k, retrievedIds.size());
        Set<String> retrievedTopK = new HashSet<>(retrievedIds.subList(0, effectiveK));
        long hitCount = retrievedTopK.stream().filter(relevantIds::contains).count();
        return (double) hitCount / relevantIds.size();
    }

    /**
     * 计算 Precision@K。
     *
     * @param relevantIds  全部相关文档 ID 集合
     * @param retrievedIds 检索返回的文档 ID 列表（顺序敏感，前 K 个用于计算）
     * @param k            截断位置
     * @return Precision@K 值（0.0 ~ 1.0）
     */
    public static double precisionAtK(Set<String> relevantIds, List<String> retrievedIds, int k) {
        if (relevantIds == null || relevantIds.isEmpty() || retrievedIds == null || retrievedIds.isEmpty()) {
            return 0.0;
        }
        int effectiveK = Math.min(k, retrievedIds.size());
        Set<String> retrievedTopK = new HashSet<>(retrievedIds.subList(0, effectiveK));
        long hitCount = retrievedTopK.stream().filter(relevantIds::contains).count();
        return (double) hitCount / effectiveK;
    }

    /**
     * 计算 MRR（Mean Reciprocal Rank）。
     * <p>
     * 针对单个查询：1 / rank_of_first_relevant_result。
     * 若检索结果中没有任何相关文档，返回 0。
     * </p>
     *
     * @param relevantIds  全部相关文档 ID 集合
     * @param retrievedIds 检索返回的文档 ID 列表（顺序敏感）
     * @return MRR 值（0.0 ~ 1.0）
     */
    public static double mrr(Set<String> relevantIds, List<String> retrievedIds) {
        if (relevantIds == null || relevantIds.isEmpty() || retrievedIds == null || retrievedIds.isEmpty()) {
            return 0.0;
        }
        for (int i = 0; i < retrievedIds.size(); i++) {
            if (relevantIds.contains(retrievedIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * 计算 nDCG@K（归一化折损累计增益）。
     * <p>
     * 假设相关文档的有级相关性分数为 1（二值相关），
     * 用于评估排序质量的标准化指标。
     * </p>
     *
     * @param relevantIds  全部相关文档 ID 集合
     * @param retrievedIds 检索返回的文档 ID 列表（顺序敏感）
     * @param k            截断位置
     * @return nDCG@K 值（0.0 ~ 1.0）
     */
    public static double ndcgAtK(Set<String> relevantIds, List<String> retrievedIds, int k) {
        if (relevantIds == null || relevantIds.isEmpty() || retrievedIds == null || retrievedIds.isEmpty()) {
            return 0.0;
        }
        int effectiveK = Math.min(k, retrievedIds.size());

        // 实际 DCG
        double dcg = 0.0;
        for (int i = 0; i < effectiveK; i++) {
            if (relevantIds.contains(retrievedIds.get(i))) {
                // 位置 i 从 1 开始（i=0 → rank=1）
                dcg += 1.0 / log2(i + 2); // +2 因为 log2(1)=0 会除零
            }
        }

        // 理想 DCG：假设所有相关文档排在前面
        int totalRelevant = Math.min(relevantIds.size(), effectiveK);
        double idcg = 0.0;
        for (int i = 0; i < totalRelevant; i++) {
            idcg += 1.0 / log2(i + 2);
        }

        return idcg > 0 ? dcg / idcg : 0.0;
    }

    /**
     * 批量计算多个查询的平均 Recall@K。
     *
     * @param queryResults 每个查询的 {相关文档集, 检索结果列表}
     * @param k            截断位置
     * @return 平均 Recall@K
     */
    public static double avgRecallAtK(List<QueryResult> queryResults, int k) {
        if (queryResults == null || queryResults.isEmpty()) return 0.0;
        return queryResults.stream()
                .mapToDouble(qr -> recallAtK(qr.relevantIds, qr.retrievedIds, k))
                .average()
                .orElse(0.0);
    }

    /**
     * 批量计算多个查询的平均 MRR。
     *
     * @param queryResults 每个查询的 {相关文档集, 检索结果列表}
     * @return 平均 MRR
     */
    public static double avgMrr(List<QueryResult> queryResults) {
        if (queryResults == null || queryResults.isEmpty()) return 0.0;
        return queryResults.stream()
                .mapToDouble(qr -> mrr(qr.relevantIds, qr.retrievedIds))
                .average()
                .orElse(0.0);
    }

    /**
     * 批量计算多个查询的平均 nDCG@K。
     *
     * @param queryResults 每个查询的 {相关文档集, 检索结果列表}
     * @param k            截断位置
     * @return 平均 nDCG@K
     */
    public static double avgNdcgAtK(List<QueryResult> queryResults, int k) {
        if (queryResults == null || queryResults.isEmpty()) return 0.0;
        return queryResults.stream()
                .mapToDouble(qr -> ndcgAtK(qr.relevantIds, qr.retrievedIds, k))
                .average()
                .orElse(0.0);
    }

    private static double log2(int n) {
        return Math.log(n) / Math.log(2);
    }

    /**
     * 单个查询的评估数据：相关文档集 + 检索结果列表。
     */
    public record QueryResult(
            String query,
            Set<String> relevantIds,
            List<String> retrievedIds
    ) {}

    /**
     * 单个指标的计算结果。
     */
    public record MetricResult(
            String metricName,
            double value,
            int k
    ) {
        @Override
        public String toString() {
            return k > 0
                    ? String.format("%s@%d = %.4f", metricName, k, value)
                    : String.format("%s = %.4f", metricName, value);
        }
    }
}
