package com.example.smartassistant.common.rag.pipeline;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.error.AgentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagSearchPipeline 异常分级验证：
 * <ul>
 *   <li>Handler 抛 {@link AgentException}（标准错误码）→ 归一为对应 errorCode 记录，不中断后续 Handler</li>
 *   <li>Handler 抛裸 {@link RuntimeException} → 归一为 {@code UNCLASSIFIED} 记录</li>
 *   <li>任一异常 → 上下文 {@code degraded=true}，但仍正常完成</li>
 * </ul>
 */
class RagSearchPipelineGradingTest {

    @Test
    @DisplayName("标准错误码 → 分级记录且不中断管线")
    void standardError_isGradedAndPipelineContinues() {
        RagSearchHandler faulty = new RagSearchHandler() {
            public void handle(RagSearchContext ctx) {
                throw new AgentException(AgentErrorCode.RAG_EMBEDDING_UNAVAILABLE, "embedding down");
            }

            public int getOrder() {
                return 10;
            }
        };
        RagSearchHandler healthy = new RagSearchHandler() {
            public void handle(RagSearchContext ctx) {
                ctx.setFusedResults(List.of(new RagSearchContext.RankedItem("ok", 1.0)));
            }

            public int getOrder() {
                return 20;
            }
        };

        RagSearchPipeline pipeline = new RagSearchPipeline(List.of(faulty, healthy));
        RagSearchContext ctx = pipeline.execute(new RagSearchContext("q"));

        assertTrue(ctx.isDegraded(), "任一 Handler 抛异常应标记降级");
        assertEquals(1, ctx.getErrors().size(), "应记录 1 个结构化错误");
        var err = ctx.getErrors().get(0);
        assertEquals("RAG_EMBEDDING_UNAVAILABLE", err.errorCode());
        assertNotNull(err.handlerName());
        assertNotNull(err.message());
        // 管线未中断：后续 healthy handler 正常执行
        assertEquals(1, ctx.getFusedResults().size());
    }

    @Test
    @DisplayName("裸 RuntimeException → 记为 UNCLASSIFIED")
    void rawException_isUnclassified() {
        RagSearchHandler faulty = new RagSearchHandler() {
            public void handle(RagSearchContext ctx) {
                throw new RuntimeException("boom");
            }

            public int getOrder() {
                return 10;
            }
        };

        RagSearchPipeline pipeline = new RagSearchPipeline(List.of(faulty));
        RagSearchContext ctx = pipeline.execute(new RagSearchContext("q"));

        assertTrue(ctx.isDegraded());
        assertEquals(1, ctx.getErrors().size());
        assertEquals("UNCLASSIFIED", ctx.getErrors().get(0).errorCode());
    }

    @Test
    @DisplayName("全部 Handler 正常 → 不降级、无错误记录")
    void allHealthy_noDegradation() {
        RagSearchHandler h = new RagSearchHandler() {
            public void handle(RagSearchContext ctx) {
                ctx.setFusedResults(List.of(new RagSearchContext.RankedItem("x", 1.0)));
            }

            public int getOrder() {
                return 10;
            }
        };
        RagSearchPipeline pipeline = new RagSearchPipeline(List.of(h));
        RagSearchContext ctx = pipeline.execute(new RagSearchContext("q"));
        assertFalse(ctx.isDegraded());
        assertTrue(ctx.getErrors().isEmpty());
        assertEquals(1, ctx.getFusedResults().size());
    }
}
