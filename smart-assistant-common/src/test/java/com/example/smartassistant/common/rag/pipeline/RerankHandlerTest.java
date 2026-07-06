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
}
