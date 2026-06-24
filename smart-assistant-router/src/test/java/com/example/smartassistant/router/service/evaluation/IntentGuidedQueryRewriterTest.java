package com.example.smartassistant.router.service.evaluation;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IntentGuidedQueryRewriter 单元测试。
 *
 * <h3>变更前后对比</h3>
 * <table>
 *   <tr><th>特性</th><th>修改前</th><th>修改后</th></tr>
 *   <tr><td>查询改写</td><td>无</td><td>4种策略：多跳→分解、模糊→扩展、精确→保留、对话→指代消解</td></tr>
 *   <tr><td>多意图分解</td><td>全部串行处理</td><td>拆为独立子查询，可并行执行</td></tr>
 *   <tr><td>模糊扩展</td><td>原样传递</td><td>自动补充地点/日期/价格约束信息</td></tr>
 * </table>
 */
class IntentGuidedQueryRewriterTest {

    private final IntentGuidedQueryRewriter rewriter = new IntentGuidedQueryRewriter();

    // ==================== 多意图分解 ====================

    @Test
    @DisplayName("多意图 → 查询分解")
    void testMultiIntentDecomposition() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setIntentCategory("ORDER");
        List<Map<String, Object>> subIntents = new ArrayList<>();
        subIntents.add(Map.of("intent", "查票", "description", "查明天杭州到上海的票", "order", 1));
        subIntents.add(Map.of("intent", "预订", "description", "有合适的就订", "depends_on", "查票", "order", 2));
        analysis.setSubIntents(subIntents);

        var result = rewriter.rewrite("查明天去上海的票，有合适的就订", analysis);

        assertEquals("decomposition", result.rewriteStrategy());
        assertEquals(2, result.subQueries().size());
        assertTrue(result.subQueries().contains("查明天杭州到上海的票"));
    }

    // ==================== 模糊扩展 ====================

    @Test
    @DisplayName("COMPLEX 意图 → 查询扩展（补充实体信息）")
    void testFuzzyExpansion() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setIntentCategory("COMPLEX");
        analysis.setEntities(Map.of(
                "location", "杭州",
                "date", "明天"
        ));

        var result = rewriter.rewrite("杭州有什么好玩的", analysis);

        assertEquals("expansion", result.rewriteStrategy());
        assertTrue(result.rewrittenQuery().contains("杭州"));
    }

    // ==================== 精确保留 ====================

    @Test
    @DisplayName("ORDER 意图 → 精确保留")
    void testPrecisionKeep() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setIntentCategory("ORDER");

        var result = rewriter.rewrite("查一下订单ORD123456的物流", analysis);

        assertEquals("precision", result.rewriteStrategy());
        assertEquals("查一下订单ORD123456的物流", result.rewrittenQuery());
    }

    // ==================== 指代消解 ====================

    @Test
    @DisplayName("指代消解: '它' → 订单号")
    void testAnaphoraResolution() {
        String resolved = IntentGuidedQueryRewriter.resolveAnaphora(
                "退掉它", "我要退订单ORD888888");
        assertEquals("退掉订单ORD888888", resolved);
    }

    @Test
    @DisplayName("无指代时保持原样")
    void testNoAnaphora() {
        String resolved = IntentGuidedQueryRewriter.resolveAnaphora(
                "查物流", "订单ORD123456");
        assertEquals("查物流", resolved);
    }

    // ==================== 标准化输入优先 ====================

    @Test
    @DisplayName("标准化输入优先于原始查询")
    void testStandardizedInputPreferred() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setIntentCategory("GENERAL");
        analysis.setStandardizedInput("杭州天气");

        var result = rewriter.rewrite("杭洲天气", analysis);

        assertEquals("杭州天气", result.rewrittenQuery());
    }

    // ==================== 边界情况 ====================

    @Test
    @DisplayName("null 意图 → 不改写")
    void testNullIntent() {
        var result = rewriter.rewrite("hello", null);
        assertEquals("none", result.rewriteStrategy());
    }

    @Test
    @DisplayName("空查询 → 不改写")
    void testEmptyQuery() {
        var result = rewriter.rewrite("", null);
        assertEquals("none", result.rewriteStrategy());
    }
}
