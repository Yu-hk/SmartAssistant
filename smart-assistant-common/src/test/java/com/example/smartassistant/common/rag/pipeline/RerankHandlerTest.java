package com.example.smartassistant.common.rag.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RerankHandler} 单元测试。
 */
class RerankHandlerTest {

    @Test
    @DisplayName("禁用时不应修改结果")
    void disabledShouldNotChange() {
        RerankHandler handler = new RerankHandler(null, false, 5);
        RagSearchContext ctx = new RagSearchContext("query");
        ctx.setFusedResults(List.of(
                new RagSearchContext.RankedItem("item1", 1.0),
                new RagSearchContext.RankedItem("item2", 0.5)
        ));
        handler.handle(ctx);
        assertEquals(2, ctx.getFusedResults().size());
    }

    @Test
    @DisplayName("评分器应能重新排序")
    void scorerShouldRerank() {
        // 评分器：文本越短分数越高
        var scorer = (java.util.function.BiFunction<String, String, Double>)
                (query, doc) -> (double) (100 - doc.length());

        RerankHandler handler = new RerankHandler(scorer, true, 5);
        RagSearchContext ctx = new RagSearchContext("query");
        ctx.setFusedResults(List.of(
                new RagSearchContext.RankedItem("很长的文本内容。。。。。。", 1.0),
                new RagSearchContext.RankedItem("短文本", 0.5)
        ));
        handler.handle(ctx);
        assertEquals(2, ctx.getFusedResults().size());
        // 短文本应排前面
        assertEquals("短文本", ctx.getFusedResults().get(0).getContent());
    }

    @Test
    @DisplayName("空结果不应抛出异常")
    void emptyResultsNoException() {
        RerankHandler handler = new RerankHandler((q, d) -> 0.5, true, 5);
        RagSearchContext ctx = new RagSearchContext("test");
        assertDoesNotThrow(() -> handler.handle(ctx));
    }

    @Test
    @DisplayName("Top-K 应限制结果数量")
    void topKShouldLimit() {
        var scorer = (java.util.function.BiFunction<String, String, Double>)
                (query, doc) -> 0.5;
        RerankHandler handler = new RerankHandler(scorer, true, 2);
        RagSearchContext ctx = new RagSearchContext("query");
        ctx.setFusedResults(List.of(
                new RagSearchContext.RankedItem("a", 0.5),
                new RagSearchContext.RankedItem("b", 0.5),
                new RagSearchContext.RankedItem("c", 0.5),
                new RagSearchContext.RankedItem("d", 0.5)
        ));
        handler.handle(ctx);
        assertEquals(2, ctx.getFusedResults().size());
    }

    @Test
    @DisplayName("自适应 resolver 应按查询解析 K 截断")
    void adaptiveResolverShouldPickK() {
        var scorer = (java.util.function.BiFunction<String, String, Double>)
                (query, doc) -> 0.5;
        // 事实型查询 → 3 条；开放式 → 8 条
        var resolver = (java.util.function.Function<String, Integer>)
                q -> q.contains("多少钱") ? 3 : 8;
        RerankHandler handler = new RerankHandler(scorer, true, 5, resolver);

        // 事实型：候选 10 条，应截断为 3
        RagSearchContext factCtx = new RagSearchContext("这款多少钱");
        List<RagSearchContext.RankedItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            items.add(new RagSearchContext.RankedItem("item" + i, 1.0 - i * 0.01));
        }
        factCtx.setFusedResults(items);
        handler.handle(factCtx);
        assertEquals(3, factCtx.getFusedResults().size(), "事实型查询应截断为 3 条");
    }

    @Test
    @DisplayName("自适应 resolver 需要的 K 超过候选数时应钳制为候选数")
    void adaptiveResolverClampedToCandidateCount() {
        var scorer = (java.util.function.BiFunction<String, String, Double>)
                (query, doc) -> 0.5;
        var resolver = (java.util.function.Function<String, Integer>) q -> 99; // 远超候选
        RerankHandler handler = new RerankHandler(scorer, true, 5, resolver);

        RagSearchContext ctx = new RagSearchContext("对比一下区别");
        ctx.setFusedResults(List.of(
                new RagSearchContext.RankedItem("a", 0.5),
                new RagSearchContext.RankedItem("b", 0.5)
        ));
        handler.handle(ctx);
        assertEquals(2, ctx.getFusedResults().size(), "K 应被候选数(2)钳制");
    }
}
