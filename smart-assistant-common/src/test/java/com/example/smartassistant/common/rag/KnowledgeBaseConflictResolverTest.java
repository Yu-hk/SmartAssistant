/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.rag.retrieval.CrossDocumentConflictResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 检索侧跨文档冲突消解（Q6 第二层）在 PgVector / Milvus 知识库上的落地验证。
 * <p>
 * 直接调用两个 KB 的 package-private 静态方法 {@code applyConflictResolution}，
 * 无需数据库基础设施即可验证「高权威胜出、低权威败方扣分」逻辑。
 * </p>
 */
public class KnowledgeBaseConflictResolverTest {

    private static KnowledgeDocument officialDoc() {
        return new KnowledgeDocument("doc-official", "官方退款政策", "该功能支持退款",
                "退款", "退款", 0, 0, "", "v1", "", 0, "",
                AuthorityLevel.L1_OFFICIAL, DocumentStatus.ACTIVE);
    }

    private static KnowledgeDocument noteDoc() {
        return new KnowledgeDocument("doc-note", "用户笔记", "该功能不支持退款",
                "退款", "退款", 0, 0, "", "v1", "", 0, "",
                AuthorityLevel.L3_NOTE, DocumentStatus.ACTIVE);
    }

    private static KnowledgeHit findById(List<KnowledgeHit> hits, String id) {
        return hits.stream()
                .filter(h -> h.getDocument().getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void pgVectorResolverPenalizesLowAuthorityOnConflict() {
        CrossDocumentConflictResolver resolver = new CrossDocumentConflictResolver();
        List<KnowledgeHit> hits = List.of(
                new KnowledgeHit(officialDoc(), 0.9),
                new KnowledgeHit(noteDoc(), 0.9));

        List<KnowledgeHit> resolved = PgVectorKnowledgeBase.applyConflictResolution(resolver, hits);

        assertEquals(2, resolved.size());
        // 胜方（L1 官方）保持原分
        assertEquals(0.9, findById(resolved, "doc-official").getScore(), 1e-9);
        // 败方（L3 笔记）按 0.30 扣分率降权 → 0.9 * (1 - 0.30) = 0.63
        assertEquals(0.9 * 0.7, findById(resolved, "doc-note").getScore(), 1e-9);
    }

    @Test
    void milvusResolverPenalizesLowAuthorityOnConflict() {
        CrossDocumentConflictResolver resolver = new CrossDocumentConflictResolver();
        List<KnowledgeHit> hits = List.of(
                new KnowledgeHit(officialDoc(), 0.9),
                new KnowledgeHit(noteDoc(), 0.9));

        List<KnowledgeHit> resolved = MilvusKnowledgeBase.applyConflictResolution(resolver, hits);

        assertEquals(2, resolved.size());
        assertEquals(0.9, findById(resolved, "doc-official").getScore(), 1e-9);
        assertEquals(0.9 * 0.7, findById(resolved, "doc-note").getScore(), 1e-9);
    }

    @Test
    void nullResolverReturnsHitsUnchanged() {
        List<KnowledgeHit> hits = List.of(
                new KnowledgeHit(officialDoc(), 0.9),
                new KnowledgeHit(noteDoc(), 0.9));

        List<KnowledgeHit> resolved = PgVectorKnowledgeBase.applyConflictResolution(null, hits);
        // null 消解器时原样透传（同一引用，无扣分、无新建对象）
        assertSame(hits, resolved);
    }

    @Test
    void singleHitSkipsResolution() {
        CrossDocumentConflictResolver resolver = new CrossDocumentConflictResolver();
        List<KnowledgeHit> hits = List.of(new KnowledgeHit(officialDoc(), 0.9));

        List<KnowledgeHit> resolved = MilvusKnowledgeBase.applyConflictResolution(resolver, hits);

        assertEquals(1, resolved.size());
        assertEquals(0.9, resolved.get(0).getScore(), 1e-9);
    }
}
