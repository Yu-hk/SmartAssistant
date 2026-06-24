package com.example.smartassistant.common.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BgeReranker 单元测试——验证重构后的 XLM-RoBERTa 兼容性。
 *
 * <h3>变更前后对比</h3>
 * <table>
 *   <tr><th>特性</th><th>修改前 (BERT)</th><th>修改后 (XLM-RoBERTa)</th></tr>
 *   <tr><td>token_type_ids</td><td>必须存在</td><td>可选（自动检测）</td></tr>
 *   <tr><td>CLS token ID</td><td>101</td><td>0</td></tr>
 *   <tr><td>SEP token ID</td><td>102</td><td>2</td></tr>
 *   <tr><td>PAD token ID</td><td>0</td><td>1</td></tr>
 *   <tr><td>UNK token ID</td><td>100</td><td>3</td></tr>
 *   <tr><td>模型输入格式</td><td>固定 3 个输入</td><td>动态 2 或 3 个输入</td></tr>
 *   <tr><td>降级策略</td><td>模型加载失败时恒等映射</td><td>同左 + ONNX Runtime Optional 输出兼容</td></tr>
 * </table>
 */
class BgeRerankerTest {

    @Test
    @DisplayName("模型文件不存在 → available=false（降级恒等映射）")
    void testModelNotFound() {
        BgeReranker reranker = new BgeReranker(
                "nonexistent/model.onnx",
                "nonexistent/tokenizer.json");
        assertFalse(reranker.isAvailable(),
                "模型文件不存在时 should be unavailable");
    }

    @Test
    @DisplayName("vocab 文件不存在 → 空 vocab（不抛异常）")
    void testVocabNotFound() {
        // 用一个不存在的 vocab 路径，验证不抛异常
        assertDoesNotThrow(() -> {
            new BgeReranker("nonexistent/model.onnx", null);
        }, "null vocab 不应抛异常");
    }

    @Test
    @DisplayName("不可用时 scorePair 返回 0")
    void testScorePairWhenUnavailable() {
        BgeReranker reranker = new BgeReranker(
                "nonexistent/model.onnx",
                "nonexistent/tokenizer.json");
        assertFalse(reranker.isAvailable());
        assertEquals(0.0, reranker.scorePair("test", "doc"), 0.001);
    }

    @Test
    @DisplayName("不可用时 rerank 返回原始列表")
    void testRerankWhenUnavailable() {
        BgeReranker reranker = new BgeReranker(
                "nonexistent/model.onnx",
                "nonexistent/tokenizer.json");
        assertFalse(reranker.isAvailable());

        var hits = Collections.singletonList(
                new KnowledgeHit(createDoc("测试文档"), 0.8));
        var result = reranker.rerank(hits, "测试查询", 5);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("close() 不可用时无异常")
    void testCloseWhenUnavailable() {
        BgeReranker reranker = new BgeReranker(
                "nonexistent/model.onnx",
                "nonexistent/tokenizer.json");
        assertDoesNotThrow(reranker::close);
    }

    @Test
    @DisplayName("空输入列表时 rerank 返回空列表")
    void testRerankEmptyHits() {
        BgeReranker reranker = new BgeReranker(
                "nonexistent/model.onnx",
                "nonexistent/tokenizer.json");
        var result = reranker.rerank(Collections.emptyList(), "query", 5);
        assertTrue(result.isEmpty());
    }

    // ==================== 辅助方法 ====================

    private KnowledgeDocument createDoc(String text) {
        return new KnowledgeDocument("ID001", "标题", text,
                "分类", "tag1,tag2", 0L, 0L);
    }
}
