package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BgeReranker 单元测试。
 */
class BgeRerankerTest {

    /** 回退：当 BGE 模型不可用时测试 identity 行为 */
    @Test
    @DisplayName("非空候选应正常返回")
    void rerank_withEmptyEmbeddingModel_shouldReturnIdentity() {
        // 模拟 BGE 不可用：null 模型
        BgeReranker reranker = new BgeReranker(null);
        KnowledgeDocument doc = new KnowledgeDocument(
                "doc-001", "测试", "内容", "", "", -1, -1);
        List<KnowledgeHit> hits = List.of(new KnowledgeHit(doc, 0.9));

        List<KnowledgeHit> result = reranker.rerank(hits, "test query", 5);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("空候选应返回空列表")
    void rerank_withEmptyHits_shouldReturnEmpty() {
        BgeReranker reranker = new BgeReranker(null);
        List<KnowledgeHit> result = reranker.rerank(List.of(), "test", 5);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("topK 应限制返回结果数")
    void rerank_shouldLimitByTopK() {
        BgeReranker reranker = new BgeReranker(null);
        KnowledgeDocument doc1 = new KnowledgeDocument("d1", "t1", "c1", "", "", -1, -1);
        KnowledgeDocument doc2 = new KnowledgeDocument("d2", "t2", "c2", "", "", -1, -1);
        KnowledgeDocument doc3 = new KnowledgeDocument("d3", "t3", "c3", "", "", -1, -1);
        List<KnowledgeHit> hits = List.of(
                new KnowledgeHit(doc1, 0.9),
                new KnowledgeHit(doc2, 0.8),
                new KnowledgeHit(doc3, 0.7));

        List<KnowledgeHit> result = reranker.rerank(hits, "test", 2);
        assertEquals(2, result.size());
    }
}
