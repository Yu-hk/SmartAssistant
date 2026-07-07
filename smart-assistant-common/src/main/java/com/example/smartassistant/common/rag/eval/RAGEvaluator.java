/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * RAG 评测编排器 — 对一条查询 + 检索结果的端到端评测。
 * <p>
 * 对应面试题 Q03 + Q29，整合检索指标计算、Faithfulness 校验、
 * 幻觉检测为一次评测调用。
 * </p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * RAGEvaluator evaluator = new RAGEvaluator();
 *
 * // 准备数据
 * Set<String> relevant = Set.of("doc1", "doc2");
 * List<String> retrieved = List.of("doc1", "doc3", "doc4", "doc2");
 * String context = buildContextString(docs);
 * String answer = "答案文本...";
 *
 * // 执行评测
 * RAGEvaluationResult result = evaluator.evaluate(
 *     "查询文本", relevant, retrieved, context, answer, 5);
 *
 * System.out.println(result);
 * }</pre>
 *
 * @author Yu-hk
 * @since 2026-07-06
 */
public class RAGEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RAGEvaluator.class);

    private final ContextFaithfulnessChecker faithfulnessChecker;
    private final HallucinationDetector hallucinationDetector;

    public RAGEvaluator() {
        this.faithfulnessChecker = new ContextFaithfulnessChecker();
        this.hallucinationDetector = new HallucinationDetector();
    }

    /**
     * 执行单次 RAG 评测。
     *
     * @param query          查询语句
     * @param relevantIds    相关文档 ID 集合（标注的 ground truth）
     * @param retrievedIds   检索返回的文档 ID 列表（按顺序）
     * @param context        拼接后的上下文文本（用于 Faithfulness + 幻觉检测）
     * @param answer         模型生成的答案（用于 Faithfulness + 幻觉检测）
     * @param topK           计算 Recall@K / Precision@K / nDCG@K 的 K 值
     * @return 综合评测结果
     */
    public RAGEvaluationResult evaluate(
            String query,
            Set<String> relevantIds,
            List<String> retrievedIds,
            String context,
            String answer,
            int topK) {

        long startTime = System.currentTimeMillis();
        List<RetrievalMetrics.MetricResult> metrics = new ArrayList<>();

        // 1. 检索指标
        if (relevantIds != null && !relevantIds.isEmpty() && retrievedIds != null && !retrievedIds.isEmpty()) {
            double recall = RetrievalMetrics.recallAtK(relevantIds, retrievedIds, topK);
            double precision = RetrievalMetrics.precisionAtK(relevantIds, retrievedIds, topK);
            double mrr = RetrievalMetrics.mrr(relevantIds, retrievedIds);
            double ndcg = RetrievalMetrics.ndcgAtK(relevantIds, retrievedIds, topK);

            metrics.add(new RetrievalMetrics.MetricResult("Recall", recall, topK));
            metrics.add(new RetrievalMetrics.MetricResult("Precision", precision, topK));
            metrics.add(new RetrievalMetrics.MetricResult("MRR", mrr, 0));
            metrics.add(new RetrievalMetrics.MetricResult("nDCG", ndcg, topK));

            log.debug("[RAGEvaluator] 检索指标: query={}, Recall@{}={}, MRR={}, nDCG@{}={}",
                    query, topK, String.format("%.4f", recall),
                    String.format("%.4f", mrr),
                    topK, String.format("%.4f", ndcg));
        } else {
            log.warn("[RAGEvaluator] 检索指标跳过: relevantIds或retrievedIds为空");
        }

        // 2. Faithfulness 校验
        ContextFaithfulnessChecker.FaithfulnessResult faithfulnessResult = null;
        if (answer != null && context != null) {
            faithfulnessResult = faithfulnessChecker.checkFaithfulness(answer, context);
            log.debug("[RAGEvaluator] Faithfulness: passed={}, score={}",
                    faithfulnessResult.passed(), String.format("%.4f", faithfulnessResult.score()));
        }

        // 3. 幻觉检测
        HallucinationDetector.HallucinationResult hallucinationResult = null;
        if (answer != null && context != null) {
            hallucinationResult = hallucinationDetector.detect(answer, context);
            log.debug("[RAGEvaluator] 幻觉率: {}",
                    String.format("%.4f", hallucinationResult.hallucinationRate()));
        }

        long latency = System.currentTimeMillis() - startTime;

        return new RAGEvaluationResult(query, metrics, faithfulnessResult, hallucinationResult, latency);
    }

    /**
     * 批量评测（TopK 默认值 5）。
     */
    public List<RAGEvaluationResult> evaluateBatch(List<EvalRequest> requests) {
        List<RAGEvaluationResult> results = new ArrayList<>();
        for (EvalRequest req : requests) {
            try {
                RAGEvaluationResult result = evaluate(
                        req.query, req.relevantIds, req.retrievedIds,
                        req.context, req.answer, req.topK > 0 ? req.topK : 5);
                results.add(result);
            } catch (Exception e) {
                log.error("[RAGEvaluator] 评测异常: query={}, error={}", req.query, e.getMessage());
            }
        }
        return results;
    }

    /**
     * 评测请求体。
     */
    public static class EvalRequest {
        final String query;
        final Set<String> relevantIds;
        final List<String> retrievedIds;
        final String context;
        final String answer;
        final int topK;

        public EvalRequest(String query, Set<String> relevantIds, List<String> retrievedIds,
                           String context, String answer, int topK) {
            this.query = query;
            this.relevantIds = relevantIds;
            this.retrievedIds = retrievedIds;
            this.context = context;
            this.answer = answer;
            this.topK = topK;
        }
    }
}
