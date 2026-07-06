package com.example.smartassistant.common.rag.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DedupHandler} 单元测试。
 */
class DedupHandlerTest {

    @Test
    @DisplayName("精确去重：完全重复应移除")
    void exactDedupRemovesDuplicates() {
        DedupHandler handler = new DedupHandler(true, DedupHandler.DedupMode.EXACT, 0.85);
        RagSearchContext ctx = new RagSearchContext("test");
        ctx.setFusedResults(List.of(
                new RagSearchContext.RankedItem("内容A", 1.0),
                new RagSearchContext.RankedItem("内容B", 0.8),
                new RagSearchContext.RankedItem("内容A", 0.6)  // 重复
        ));
        handler.handle(ctx);
        assertEquals(2, ctx.getFusedResults().size());
    }

    @Test
    @DisplayName("精确去重：完全不同的内容应保留")
    void exactDedupKeepsDifferent() {
        DedupHandler handler = new DedupHandler(true, DedupHandler.DedupMode.EXACT, 0.85);
        RagSearchContext ctx = new RagSearchContext("test");
        ctx.setFusedResults(List.of(
                new RagSearchContext.RankedItem("iPhone 15 价格", 1.0),
                new RagSearchContext.RankedItem("MacBook Pro 配置", 0.8),
                new RagSearchContext.RankedItem("AirPods 评测", 0.6)
        ));
        handler.handle(ctx);
        assertEquals(3, ctx.getFusedResults().size());
    }

    @Test
    @DisplayName("模糊去重：高度相似的应移除")
    void aggressiveDedupRemovesSimilar() {
        DedupHandler handler = new DedupHandler(true, DedupHandler.DedupMode.AGGRESSIVE, 0.50);
        // 几乎相同的文本（仅几个字不同）
        String base = "iPhone15Pro是一款高性能智能手机搭载A17Pro芯片";
        String similar = "iPhone15Pro是一款高性能智能手机搭载A17Pro芯";

        RagSearchContext ctx = new RagSearchContext("test");
        ctx.setFusedResults(List.of(
                new RagSearchContext.RankedItem(base, 1.0),
                new RagSearchContext.RankedItem(similar, 0.9)
        ));
        handler.handle(ctx);
        assertEquals(1, ctx.getFusedResults().size());
    }

    @Test
    @DisplayName("禁用时不应修改结果")
    void disabledShouldNotModify() {
        DedupHandler handler = new DedupHandler(false, DedupHandler.DedupMode.AGGRESSIVE, 0.85);
        RagSearchContext ctx = new RagSearchContext("test");
        ctx.setFusedResults(List.of(
                new RagSearchContext.RankedItem("A", 1.0),
                new RagSearchContext.RankedItem("A", 0.5)
        ));
        handler.handle(ctx);
        assertEquals(2, ctx.getFusedResults().size());
    }

    @Test
    @DisplayName("空结果不应抛出异常")
    void emptyResultsNoException() {
        DedupHandler handler = new DedupHandler(true, DedupHandler.DedupMode.AGGRESSIVE, 0.85);
        RagSearchContext ctx = new RagSearchContext("test");
        assertDoesNotThrow(() -> handler.handle(ctx));
    }

    @Test
    @DisplayName("计算相同文本重叠度应为 1.0")
    void sameTextOverlapIsOne() {
        double overlap = DedupHandler.computeContentOverlap("Hello World", "Hello World");
        assertEquals(1.0, overlap, 1e-9);
    }

    @Test
    @DisplayName("计算完全不同文本重叠度应为 0")
    void differentTextOverlapIsZero() {
        double overlap = DedupHandler.computeContentOverlap("AAAAA", "BBBBB");
        assertTrue(overlap < 0.1);
    }
}
