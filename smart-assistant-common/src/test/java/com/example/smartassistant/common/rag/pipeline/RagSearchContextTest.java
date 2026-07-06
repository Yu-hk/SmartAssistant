/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RagSearchContext} 单元测试。
 */
class RagSearchContextTest {

    @Test
    @DisplayName("创建上下文应保留原始查询")
    void shouldPreserveOriginalQuery() {
        RagSearchContext ctx = new RagSearchContext("iPhone 15 Pro 价格");
        assertEquals("iPhone 15 Pro 价格", ctx.getOriginalQuery());
    }

    @Test
    @DisplayName("初始应包含一个 query variant")
    void shouldStartWithOneVariant() {
        RagSearchContext ctx = new RagSearchContext("测试");
        assertEquals(1, ctx.getQueryVariants().size());
        assertEquals("测试", ctx.getQueryVariants().get(0));
    }

    @Test
    @DisplayName("添加不重复的 variant")
    void shouldAddUniqueVariants() {
        RagSearchContext ctx = new RagSearchContext("original");
        ctx.addQueryVariant("variant1");
        ctx.addQueryVariant("variant2");
        ctx.addQueryVariant("variant1"); // 重复
        assertEquals(3, ctx.getQueryVariants().size());
    }

    @Test
    @DisplayName("添加 path 结果")
    void shouldAddPathResult() {
        RagSearchContext ctx = new RagSearchContext("test");
        ctx.addPathResult("BM25", List.of("result1", "result2"));
        assertFalse(ctx.getPathResults().isEmpty());
        assertEquals(2, ctx.getPathResults().get("BM25").getItems().size());
    }

    @Test
    @DisplayName("设置和获取属性")
    void shouldStoreAttributes() {
        RagSearchContext ctx = new RagSearchContext("test");
        ctx.setAttribute("key1", "value1");
        assertEquals("value1", ctx.getAttribute("key1"));
    }

    @Test
    @DisplayName("终止状态")
    void shouldSupportTermination() {
        RagSearchContext ctx = new RagSearchContext("test");
        assertFalse(ctx.isTerminated());
        ctx.setTerminated(true);
        assertTrue(ctx.isTerminated());
    }

    @Test
    @DisplayName("质量分数默认值")
    void shouldDefaultQualityScore() {
        RagSearchContext ctx = new RagSearchContext("test");
        assertEquals(0.0, ctx.getQualityScore());
    }

    @Test
    @DisplayName("RankedItem 构造与分数操作")
    void rankedItemScoring() {
        RagSearchContext.RankedItem item = new RagSearchContext.RankedItem("content", 0.5);
        assertEquals(0.5, item.getRrfScore());
        assertEquals("content", item.getContent());
        item.addScore(0.3);
        assertEquals(0.8, item.getRrfScore(), 1e-9);
    }

    @Test
    @DisplayName("RetrievalPathResult 空 items 处理")
    void retrievalPathResultEmptyItems() {
        RagSearchContext.RetrievalPathResult path = new RagSearchContext.RetrievalPathResult("test", null);
        assertTrue(path.isEmpty());
        assertEquals("test", path.getPathName());
    }

    @Test
    @DisplayName("设置 fusedResults 应替换而非追加")
    void fusedResultsShouldReplace() {
        RagSearchContext ctx = new RagSearchContext("test");
        ctx.addPathResult("path1", List.of("a", "b"));

        List<RagSearchContext.RankedItem> items = List.of(
                new RagSearchContext.RankedItem("a", 1.0));
        ctx.setFusedResults(items);
        assertEquals(1, ctx.getFusedResults().size());
    }
}
