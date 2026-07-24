/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.retrieval;

import com.example.smartassistant.common.rag.AclContext;
import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.KnowledgeHit;
import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parent-Child 检索侧取父块测试（P0：small-to-big）。
 * <p>
 * 覆盖 {@link ParentChildExpander} 的核心行为与 {@link KnowledgeRetrievalService} 的接线：
 * 命中子块→取父块替换、同父块去重、父块缺失兜底、独立文档原样保留、开关可关。
 * </p>
 */
class ParentChildRetrievalTest {

    // ==================== 测试桩 ====================

    /**
     * 可控知识库桩：{@code search} 返回预置命中；{@code getById} 从 map 反查（支持父块反查）。
     */
    static class StubKnowledgeBase implements KnowledgeBase {
        private final String name;
        private final Map<String, KnowledgeDocument> store = new ConcurrentHashMap<>();
        private List<KnowledgeHit> searchResult = new ArrayList<>();

        StubKnowledgeBase(String name) {
            this.name = name;
        }

        StubKnowledgeBase put(KnowledgeDocument doc) {
            store.put(doc.getId(), doc);
            return this;
        }

        StubKnowledgeBase withSearchResult(List<KnowledgeHit> hits) {
            this.searchResult = hits;
            return this;
        }

        @Override public String getName() { return name; }
        @Override public void addDocument(KnowledgeDocument doc) { put(doc); }
        @Override public void removeDocument(String id) { store.remove(id); }
        @Override public List<String> listIdsByBaseDocId(String baseDocId) { return List.of(); }
        @Override public void updateStatus(String docId, com.example.smartassistant.common.rag.DocumentStatus s) { }
        @Override public List<KnowledgeHit> search(String query, int topK, String tenantId) { return searchResult; }
        @Override public KnowledgeDocument getById(String id) { return id == null ? null : store.get(id); }
        @Override public int size() { return store.size(); }
        @Override public void reindex() { }
    }

    private static KnowledgeDocument doc(String id, String title, String content, String parentId) {
        return new KnowledgeDocument(id, title, content, "cat", "kw",
                -1, -1, "", "v1", "", 0, parentId);
    }

    // ==================== ParentChildExpander 单元行为 ====================

    @Test
    void childHitShouldBeReplacedByParentContent() {
        KnowledgeDocument parent = doc("D-parent-0", "退款政策",
                "退款政策全文：7天无理由，原路退回，3个工作日内到账，节假日顺延。", "");
        KnowledgeDocument child = doc("D-parent-0-child-1", "退款政策",
                "3个工作日内到账", "D-parent-0");
        StubKnowledgeBase kb = new StubKnowledgeBase("kb").put(parent);

        List<KnowledgeHit> expanded = ParentChildExpander.expand(
                List.of(new KnowledgeHit(child, 0.9)), kb);

        assertEquals(1, expanded.size());
        assertEquals("D-parent-0", expanded.get(0).getDocument().getId(),
                "命中子块应被替换为其父块");
        assertTrue(expanded.get(0).getDocument().getContent().contains("7天无理由"),
                "上下文应为父块的完整内容（small-to-big）");
        assertEquals(0.9, expanded.get(0).getScore(), 1e-9, "应沿用子块的相关度分数");
    }

    @Test
    void multipleChildrenOfSameParentShouldDedupToOneParent() {
        KnowledgeDocument parent = doc("P1-parent-0", "发货规则",
                "发货规则父块完整正文……", "");
        KnowledgeDocument c1 = doc("P1-parent-0-child-1", "发货规则", "48小时内发货", "P1-parent-0");
        KnowledgeDocument c2 = doc("P1-parent-0-child-2", "发货规则", "偏远地区顺延", "P1-parent-0");
        StubKnowledgeBase kb = new StubKnowledgeBase("kb").put(parent);

        List<KnowledgeHit> expanded = ParentChildExpander.expand(
                List.of(new KnowledgeHit(c1, 0.7), new KnowledgeHit(c2, 0.85)), kb);

        assertEquals(1, expanded.size(), "同一父块的两个子块应去重为一个父块");
        assertEquals("P1-parent-0", expanded.get(0).getDocument().getId());
        assertEquals(0.85, expanded.get(0).getScore(), 1e-9,
                "去重后应保留命中子块中的最高分作为父块代表分");
    }

    @Test
    void missingParentShouldFallbackToChild() {
        // 父块未入库 → getById 返回 null → 兜底保留子块
        StubKnowledgeBase kb = new StubKnowledgeBase("kb");
        KnowledgeDocument child = doc("X-parent-9-child-0", "标题", "子块内容", "X-parent-9");

        List<KnowledgeHit> expanded = ParentChildExpander.expand(
                List.of(new KnowledgeHit(child, 0.6)), kb);

        assertEquals(1, expanded.size());
        assertEquals("X-parent-9-child-0", expanded.get(0).getDocument().getId(),
                "父块反查失败应兜底保留子块，绝不丢证据");
    }

    @Test
    void standaloneDocWithoutParentShouldBeKept() {
        StubKnowledgeBase kb = new StubKnowledgeBase("kb");
        KnowledgeDocument standalone = doc("DOC-STANDALONE", "独立文档", "无父块的独立文档", "");

        List<KnowledgeHit> expanded = ParentChildExpander.expand(
                List.of(new KnowledgeHit(standalone, 0.5)), kb);

        assertEquals(1, expanded.size());
        assertEquals("DOC-STANDALONE", expanded.get(0).getDocument().getId());
    }

    @Test
    void resultShouldBeSortedByScoreDescending() {
        KnowledgeDocument pA = doc("A-parent-0", "A", "A父块", "");
        KnowledgeDocument pB = doc("B-parent-0", "B", "B父块", "");
        StubKnowledgeBase kb = new StubKnowledgeBase("kb").put(pA).put(pB);
        KnowledgeDocument cA = doc("A-parent-0-child-0", "A", "a", "A-parent-0");
        KnowledgeDocument cB = doc("B-parent-0-child-0", "B", "b", "B-parent-0");

        // 传入顺序 A(0.3) 在前，B(0.95) 在后；展开后应按分数重排 B 在前
        List<KnowledgeHit> expanded = ParentChildExpander.expand(
                List.of(new KnowledgeHit(cA, 0.3), new KnowledgeHit(cB, 0.95)), kb);

        assertEquals(2, expanded.size());
        assertEquals("B-parent-0", expanded.get(0).getDocument().getId(), "高分父块应排在前");
        assertEquals("A-parent-0", expanded.get(1).getDocument().getId());
    }

    @Test
    void nullOrEmptyInputShouldReturnAsIs() {
        StubKnowledgeBase kb = new StubKnowledgeBase("kb");
        assertTrue(ParentChildExpander.expand(List.of(), kb).isEmpty());
        // null 入参应原样返回 null（不抛异常）
        assertTrue(ParentChildExpander.expand(null, kb) == null);
    }

    // ==================== KnowledgeRetrievalService 接线 ====================

    @Test
    void serviceSearchShouldFeedParentContentIntoContext() {
        KnowledgeDocument parent = doc("KB-parent-0", "退款政策",
                "退款政策父块：完整流程与到账时效说明。", "");
        KnowledgeDocument child = doc("KB-parent-0-child-2", "退款政策",
                "到账时效", "KB-parent-0");
        StubKnowledgeBase kb = new StubKnowledgeBase("policy")
                .put(parent)
                .withSearchResult(List.of(new KnowledgeHit(child, 0.88)));

        KnowledgeRetrievalService service = new KnowledgeRetrievalService().register(kb);
        assertTrue(service.isParentChildExpansion(), "父子取父块默认应开启");

        String ctx = service.search("policy", "退款多久到账", 5, AclContext.forTenant(""));

        assertTrue(ctx.contains("完整流程与到账时效说明"),
                "上下文应包含父块完整内容");
        assertTrue(ctx.contains("[CID:KB-parent-0]"),
                "引用 ID 应为父块 id，便于稳定溯源");
    }

    @Test
    void serviceShouldKeepChildWhenExpansionDisabled() {
        KnowledgeDocument parent = doc("KB2-parent-0", "标题", "父块完整内容 XYZ", "");
        KnowledgeDocument child = doc("KB2-parent-0-child-0", "标题", "子块片段 ABC", "KB2-parent-0");
        StubKnowledgeBase kb = new StubKnowledgeBase("kb2")
                .put(parent)
                .withSearchResult(List.of(new KnowledgeHit(child, 0.9)));

        KnowledgeRetrievalService service = new KnowledgeRetrievalService()
                .register(kb)
                .setParentChildExpansion(false);

        String ctx = service.search("kb2", "q", 5, AclContext.forTenant(""));

        assertTrue(ctx.contains("子块片段 ABC"), "关闭开关后应保留原子块内容");
        assertFalse(ctx.contains("父块完整内容 XYZ"), "关闭开关后不应取父块");
    }
}
