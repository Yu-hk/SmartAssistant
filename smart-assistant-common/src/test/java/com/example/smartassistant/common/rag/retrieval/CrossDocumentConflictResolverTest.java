/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.retrieval;

import com.example.smartassistant.common.rag.AuthorityLevel;
import com.example.smartassistant.common.rag.DocumentStatus;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.KnowledgeHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CrossDocumentConflictResolver 单元测试——覆盖 Q6 第二层跨文档冲突消解的核心规则。
 */
class CrossDocumentConflictResolverTest {

    private static KnowledgeDocument doc(String id, String content,
                                         AuthorityLevel authority, String version) {
        return new KnowledgeDocument(
                id, "标题-" + id, content, "分类", "关键词",
                -1, -1, "", version, "", 0, "",
                authority, DocumentStatus.ACTIVE);
    }

    private static KnowledgeHit hit(KnowledgeDocument d, double score) {
        return new KnowledgeHit(d, score);
    }

    // ---------- 1. 无冲突：分数与排序不变 ----------

    @Test
    void noConflict_keepsScoresAndOrder() {
        KnowledgeDocument a = doc("A", "退款需提交在线申请", AuthorityLevel.L2_INTERNAL, "v1");
        KnowledgeDocument b = doc("B", "退货物流时效通常为三天", AuthorityLevel.L2_INTERNAL, "v1");
        List<KnowledgeHit> hits = List.of(hit(a, 0.90), hit(b, 0.80));

        var result = new CrossDocumentConflictResolver().resolve(hits);

        assertTrue(result.decisions().isEmpty(), "无冲突时不应产生决策");
        assertEquals(2, result.resolved().size());
        assertEquals(0.90, result.resolved().get(0).adjustedScore(), 1e-9);
        assertEquals(0.80, result.resolved().get(1).adjustedScore(), 1e-9);
        assertEquals("A", result.resolved().get(0).original().getDocument().getId());
    }

    // ---------- 2. 权威性压制：高权威胜，低权威败方扣分 ----------

    @Test
    void authoritySuppression_penalizesLowerAuthority() {
        KnowledgeDocument official = doc("A", "官方规定支持退款", AuthorityLevel.L1_OFFICIAL, "v1");
        KnowledgeDocument forum = doc("B", "论坛说不支持退款", AuthorityLevel.L4_EXTERNAL, "v1");
        List<KnowledgeHit> hits = List.of(hit(official, 0.90), hit(forum, 0.90));

        var result = new CrossDocumentConflictResolver().resolve(hits);

        assertEquals(1, result.decisions().size());
        var decision = result.decisions().get(0);
        assertEquals("A", decision.winnerDocId());
        assertEquals("AUTHORITY", decision.reason());

        var byId = indexById(result);
        assertEquals(0.90, byId.get("A").adjustedScore(), 1e-9, "胜方分数不变");
        assertEquals(0.90 * 0.70, byId.get("B").adjustedScore(), 1e-9, "败方按 0.30 扣分");
        assertEquals("A", result.resolved().get(0).original().getDocument().getId());
        // 败方被标记
        assertEquals(1, byId.get("B").conflictTags().size());
        assertTrue(byId.get("B").conflictTags().get(0).lost());
    }

    // ---------- 3. 版本压制：同权威，新版胜 ----------

    @Test
    void versionSuppression_penalizesStaleVersion() {
        KnowledgeDocument newDoc = doc("A", "最新版支持退款", AuthorityLevel.L2_INTERNAL, "v2");
        KnowledgeDocument oldDoc = doc("B", "旧版说不支持退款", AuthorityLevel.L2_INTERNAL, "v1");
        List<KnowledgeHit> hits = List.of(hit(newDoc, 0.85), hit(oldDoc, 0.85));

        var result = new CrossDocumentConflictResolver().resolve(hits);

        assertEquals(1, result.decisions().size());
        var decision = result.decisions().get(0);
        assertEquals("A", decision.winnerDocId());
        assertEquals("VERSION", decision.reason());

        var byId = indexById(result);
        assertEquals(0.85, byId.get("A").adjustedScore(), 1e-9);
        assertEquals(0.85 * 0.70, byId.get("B").adjustedScore(), 1e-9);
    }

    // ---------- 4. 平局：同权威同版本，双方减半扣分 ----------

    @Test
    void tie_bothPenalizedHalfRate() {
        KnowledgeDocument a = doc("A", "内部笔记支持退款", AuthorityLevel.L3_NOTE, "v1");
        KnowledgeDocument b = doc("B", "另一笔记不支持退款", AuthorityLevel.L3_NOTE, "v1");
        List<KnowledgeHit> hits = List.of(hit(a, 0.90), hit(b, 0.90));

        var result = new CrossDocumentConflictResolver().resolve(hits);

        assertEquals(1, result.decisions().size());
        var decision = result.decisions().get(0);
        assertNull(decision.winnerDocId());
        assertEquals("TIE", decision.reason());

        var byId = indexById(result);
        // 平局双方按 0.15 (=0.30/2) 扣分
        assertEquals(0.90 * 0.85, byId.get("A").adjustedScore(), 1e-9);
        assertEquals(0.90 * 0.85, byId.get("B").adjustedScore(), 1e-9);
        assertFalse(byId.get("A").conflictTags().get(0).lost());
        assertFalse(byId.get("B").conflictTags().get(0).lost());
    }

    // ---------- 5. 三方：仅冲突方被处理，无关方不受影响 ----------

    @Test
    void threeWay_onlyConflictingPairResolved() {
        KnowledgeDocument official = doc("A", "官方支持退款", AuthorityLevel.L1_OFFICIAL, "v1");
        KnowledgeDocument forum = doc("B", "论坛不支持退款", AuthorityLevel.L3_NOTE, "v1");
        KnowledgeDocument unrelated = doc("C", "退货物流时效说明", AuthorityLevel.L2_INTERNAL, "v1");
        List<KnowledgeHit> hits = List.of(hit(official, 0.95), hit(forum, 0.80), hit(unrelated, 0.70));

        var result = new CrossDocumentConflictResolver().resolve(hits);

        assertEquals(1, result.decisions().size(), "仅 A vs B 冲突");
        var byId = indexById(result);
        assertEquals(0.95, byId.get("A").adjustedScore(), 1e-9, "胜方不变");
        assertEquals(0.80 * 0.70, byId.get("B").adjustedScore(), 1e-9, "败方扣分");
        assertEquals(0.70, byId.get("C").adjustedScore(), 1e-9, "无关方不变且无标记");
        assertTrue(byId.get("C").conflictTags().isEmpty());
    }

    // ---------- 6. ScoreBreakdown 可观测明细 ----------

    @Test
    void scoreBreakdown_recorded() {
        KnowledgeDocument official = doc("A", "官方支持退款", AuthorityLevel.L1_OFFICIAL, "v1");
        KnowledgeDocument forum = doc("B", "论坛不支持退款", AuthorityLevel.L4_EXTERNAL, "v1");
        List<KnowledgeHit> hits = List.of(hit(official, 0.90), hit(forum, 0.90));

        var result = new CrossDocumentConflictResolver().resolve(hits);
        var byId = indexById(result);

        var winnerBd = byId.get("A").breakdown();
        assertEquals(0.90, winnerBd.baseScore(), 1e-9);
        assertEquals(1.0, winnerBd.authorityFactor(), 1e-9, "L1 → 1.0");
        assertEquals(0.0, winnerBd.conflictPenalty(), 1e-9);
        assertEquals(0.90, winnerBd.finalScore(), 1e-9);

        var loserBd = byId.get("B").breakdown();
        assertEquals(0.90, loserBd.baseScore(), 1e-9);
        assertEquals(0.25, loserBd.authorityFactor(), 1e-9, "L4 → 0.25");
        assertEquals(0.30, loserBd.conflictPenalty(), 1e-9);
        assertEquals(0.63, loserBd.finalScore(), 1e-9);
    }

    // ---------- 7. resolveHits 便捷方法返回调整后 KnowledgeHit ----------

    @Test
    void resolveHits_returnsAdjustedHits() {
        KnowledgeDocument official = doc("A", "官方支持退款", AuthorityLevel.L1_OFFICIAL, "v1");
        KnowledgeDocument forum = doc("B", "论坛不支持退款", AuthorityLevel.L4_EXTERNAL, "v1");
        List<KnowledgeHit> hits = List.of(hit(official, 0.90), hit(forum, 0.90));

        List<KnowledgeHit> resolved = new CrossDocumentConflictResolver().resolveHits(hits);

        assertEquals(2, resolved.size());
        assertEquals("A", resolved.get(0).getDocument().getId());
        assertEquals(0.90, resolved.get(0).getScore(), 1e-9);
        assertEquals(0.63, resolved.get(1).getScore(), 1e-9);
    }

    // ---------- 边界：空 / 单元素 ----------

    @Test
    void emptyAndSingle_passthrough() {
        assertTrue(new CrossDocumentConflictResolver().resolve(List.of()).resolved().isEmpty());
        KnowledgeDocument a = doc("A", "支持退款", AuthorityLevel.L1_OFFICIAL, "v1");
        var single = new CrossDocumentConflictResolver().resolve(List.of(hit(a, 0.5)));
        assertEquals(1, single.resolved().size());
        assertEquals(0.5, single.resolved().get(0).adjustedScore(), 1e-9);
        assertTrue(single.decisions().isEmpty());
    }

    private static java.util.Map<String, CrossDocumentConflictResolver.ResolvedHit> indexById(
            CrossDocumentConflictResolver.ConflictResolutionResult result) {
        var map = new java.util.HashMap<String, CrossDocumentConflictResolver.ResolvedHit>();
        for (var r : result.resolved()) {
            map.put(r.original().getDocument().getId(), r);
        }
        return map;
    }
}
