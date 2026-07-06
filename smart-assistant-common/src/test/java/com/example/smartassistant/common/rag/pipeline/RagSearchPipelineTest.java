/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RagSearchPipeline} 单元测试。
 */
class RagSearchPipelineTest {

    static class CountingHandler implements RagSearchHandler {
        final String name;
        final int order;
        int invokeCount = 0;

        CountingHandler(String name, int order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public void handle(RagSearchContext context) {
            invokeCount++;
            context.setAttribute("lastHandler", name);
        }

        @Override
        public int getOrder() { return order; }
    }

    static class TerminatingHandler implements RagSearchHandler {
        @Override
        public void handle(RagSearchContext context) {
            context.setTerminated(true);
        }
        @Override
        public int getOrder() { return 50; }
    }

    static class FailingHandler implements RagSearchHandler {
        @Override
        public void handle(RagSearchContext context) {
            throw new RuntimeException("模拟异常");
        }
        @Override
        public int getOrder() { return 30; }
    }

    @Test
    @DisplayName("空 Handler 列表应正常运行")
    void emptyHandlers() {
        var pipeline = new RagSearchPipeline(List.of());
        RagSearchContext ctx = new RagSearchContext("test");
        assertDoesNotThrow(() -> pipeline.execute(ctx));
    }

    @Test
    @DisplayName("Handler 应按 Order 顺序执行")
    void handlersExecuteInOrder() {
        var h1 = new CountingHandler("first", 10);
        var h2 = new CountingHandler("second", 20);
        var h3 = new CountingHandler("third", 30);
        var pipeline = new RagSearchPipeline(List.of(h3, h1, h2));

        RagSearchContext ctx = new RagSearchContext("test");
        pipeline.execute(ctx);

        assertEquals(1, h1.invokeCount);
        assertEquals(1, h2.invokeCount);
        assertEquals(1, h3.invokeCount);
        assertEquals("third", ctx.getAttribute("lastHandler"));
    }

    @Test
    @DisplayName("Handler 异常不应中断 Pipeline")
    void handlerExceptionShouldNotStop() {
        var h1 = new CountingHandler("before", 10);
        var fail = new FailingHandler();
        var h2 = new CountingHandler("after", 50);
        var pipeline = new RagSearchPipeline(List.of(h1, fail, h2));

        RagSearchContext ctx = new RagSearchContext("test");
        assertDoesNotThrow(() -> pipeline.execute(ctx));

        assertEquals(1, h1.invokeCount);
        assertEquals(1, h2.invokeCount);
    }

    @Test
    @DisplayName("终止标记应跳过后续 Handler")
    void terminationSkipsRemaining() {
        var term = new TerminatingHandler();
        var after = new CountingHandler("after", 100);
        var pipeline = new RagSearchPipeline(List.of(term, after));

        RagSearchContext ctx = new RagSearchContext("test");
        pipeline.execute(ctx);

        assertEquals(0, after.invokeCount);
    }

    @Test
    @DisplayName("getHandlers 应返回排序后的副本")
    void getHandlersReturnsSortedCopy() {
        var h1 = new CountingHandler("a", 20);
        var h2 = new CountingHandler("b", 10);
        var pipeline = new RagSearchPipeline(List.of(h1, h2));

        assertEquals(2, pipeline.getHandlers().size());
        assertEquals(10, pipeline.getHandlers().get(0).getOrder());
        assertEquals(20, pipeline.getHandlers().get(1).getOrder());
    }
}
