/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RAGEvaluator} 和 {@link RAGEvaluationResult} 的单元测试。
 *
 * @author Yu-hk
 * @since 2026-07-06
 */
class RAGEvaluatorTest {

    private final RAGEvaluator evaluator = new RAGEvaluator();

    @Test
    @DisplayName("完整评测流程：检索+忠实性+幻觉")
    void testFullEvaluation() {
        Set<String> relevant = Set.of("doc1", "doc2");
        List<String> retrieved = List.of("doc1", "doc3", "doc2", "doc4");
        String context = "[CID:doc1] 文档1信息：产品价格199元。\n[CID:doc2] 文档2信息：发货时效3-5天。";
        String answer = "该产品价格为199元[CID:doc1]，发货需要3-5天[CID:doc2]。";

        RAGEvaluationResult result = evaluator.evaluate(
                "产品价格和发货时间", relevant, retrieved, context, answer, 3);

        assertNotNull(result);
        assertTrue(result.getFaithfulnessResult() != null && result.getFaithfulnessResult().passed(),
                "Faithfulness Should pass with [CID:xxx] in both answer and context");
        assertTrue(result.compositeScore() >= 0.6, "综合评分应 >= 0.6");
        assertFalse(result.getRetrievalMetrics().isEmpty(), "应有检索指标");

        System.out.println(result);
    }

    @Test
    @DisplayName("只评测检索指标（无答案时跳过 Faithfulness）")
    void testRetrievalOnly() {
        Set<String> relevant = Set.of("docA");
        List<String> retrieved = List.of("docA", "docB");
        String context = "一些上下文内容";

        RAGEvaluationResult result = evaluator.evaluate(
                "测试查询", relevant, retrieved, context, null, 5);

        assertNotNull(result);
        assertFalse(result.getRetrievalMetrics().isEmpty());
        assertNull(result.getFaithfulnessResult(), "无答案时 Faithfulness 应为 null");
        assertNull(result.getHallucinationResult(), "无答案时幻觉检测应为 null");
    }

    @Test
    @DisplayName("空的检索数据不报错")
    void testEmptyRetrieval() {
        RAGEvaluationResult result = evaluator.evaluate(
                "空检索", Set.of(), List.of(), "上下文", "答案", 5);

        assertNotNull(result);
        assertTrue(result.getRetrievalMetrics().isEmpty(), "无检索数据时指标应为空");
    }

    @Test
    @DisplayName("批量评测汇总")
    void testBatchEvaluation() {
        var req1 = new RAGEvaluator.EvalRequest(
                "q1", Set.of("A"), List.of("A", "B"), "上下文1", "答案1", 3);
        var req2 = new RAGEvaluator.EvalRequest(
                "q2", Set.of("C"), List.of("A", "C"), "上下文2", "答案2", 3);

        List<RAGEvaluationResult> results = evaluator.evaluateBatch(List.of(req1, req2));

        assertEquals(2, results.size());
        String report = RAGEvaluationResult.generateBatchReport(results);
        assertTrue(report.contains("RAG 批量评测报告"));
        System.out.println(report);
    }
}
