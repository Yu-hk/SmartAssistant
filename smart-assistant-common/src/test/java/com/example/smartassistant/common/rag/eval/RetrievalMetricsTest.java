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
 * {@link RetrievalMetrics} 的单元测试。
 *
 * @author Yu-hk
 * @since 2026-07-06
 */
class RetrievalMetricsTest {

    @Test
    @DisplayName("Recall@K: 全部命中")
    void testRecallAtK_fullHit() {
        Set<String> relevant = Set.of("A", "B", "C");
        List<String> retrieved = List.of("A", "B", "C", "D", "E");
        assertEquals(1.0, RetrievalMetrics.recallAtK(relevant, retrieved, 3), 0.001);
    }

    @Test
    @DisplayName("Recall@K: 部分命中")
    void testRecallAtK_partialHit() {
        Set<String> relevant = Set.of("A", "B", "C");
        List<String> retrieved = List.of("A", "D", "E", "F");
        assertEquals(1.0 / 3, RetrievalMetrics.recallAtK(relevant, retrieved, 4), 0.001);
    }

    @Test
    @DisplayName("Recall@K: 无命中返回0")
    void testRecallAtK_noHit() {
        Set<String> relevant = Set.of("A", "B");
        List<String> retrieved = List.of("X", "Y", "Z");
        assertEquals(0.0, RetrievalMetrics.recallAtK(relevant, retrieved, 3), 0.001);
    }

    @Test
    @DisplayName("Precision@K: 全部相关")
    void testPrecisionAtK_fullRelevant() {
        Set<String> relevant = Set.of("A", "B", "C");
        List<String> retrieved = List.of("A", "B", "C");
        assertEquals(1.0, RetrievalMetrics.precisionAtK(relevant, retrieved, 3), 0.001);
    }

    @Test
    @DisplayName("Precision@K: 部分相关")
    void testPrecisionAtK_partial() {
        Set<String> relevant = Set.of("A");
        List<String> retrieved = List.of("A", "B", "C", "D");
        assertEquals(0.25, RetrievalMetrics.precisionAtK(relevant, retrieved, 4), 0.001);
    }

    @Test
    @DisplayName("MRR: 第一个相关在位置2")
    void testMrr_rank2() {
        Set<String> relevant = Set.of("B");
        List<String> retrieved = List.of("X", "B", "Y");
        assertEquals(0.5, RetrievalMetrics.mrr(relevant, retrieved), 0.001);
    }

    @Test
    @DisplayName("MRR: 无相关返回0")
    void testMrr_noRelevant() {
        Set<String> relevant = Set.of("Z");
        List<String> retrieved = List.of("A", "B", "C");
        assertEquals(0.0, RetrievalMetrics.mrr(relevant, retrieved), 0.001);
    }

    @Test
    @DisplayName("nDCG@K: 完美排序")
    void testNdcgAtK_perfect() {
        Set<String> relevant = Set.of("A", "B");
        List<String> retrieved = List.of("A", "B", "C", "D");
        double ndcg = RetrievalMetrics.ndcgAtK(relevant, retrieved, 4);
        assertEquals(1.0, ndcg, 0.001);
    }

    @Test
    @DisplayName("nDCG@K: 不完美排序")
    void testNdcgAtK_nonPerfect() {
        Set<String> relevant = Set.of("A", "B");
        // B 在位置 3 而非 2，DCG < IDCG
        List<String> retrieved = List.of("A", "C", "B", "D");
        double ndcg = RetrievalMetrics.ndcgAtK(relevant, retrieved, 4);
        assertTrue(ndcg > 0 && ndcg < 1.0, "nDCG should be between 0 and 1 for non-perfect ranking");
    }

    @Test
    @DisplayName("批量平均指标")
    void testBatchMetrics() {
        var qr1 = new RetrievalMetrics.QueryResult(
                "q1", Set.of("A"), List.of("A", "B"));
        var qr2 = new RetrievalMetrics.QueryResult(
                "q2", Set.of("C"), List.of("A", "C"));

        double recall1 = RetrievalMetrics.recallAtK(qr1.relevantIds(), qr1.retrievedIds(), 2);
        double recall2 = RetrievalMetrics.recallAtK(qr2.relevantIds(), qr2.retrievedIds(), 2);
        System.out.println("DEBUG recall1=" + recall1 + " recall2=" + recall2);

        double avgRecall = RetrievalMetrics.avgRecallAtK(List.of(qr1, qr2), 2);
        System.out.println("DEBUG avgRecall=" + avgRecall);

        double avgMrr = RetrievalMetrics.avgMrr(List.of(qr1, qr2));
        System.out.println("DEBUG avgMrr=" + avgMrr);

        assertTrue(avgRecall > 0, "avgRecall should be positive");
        assertTrue(avgMrr > 0, "avgMrr should be positive");
    }
}
