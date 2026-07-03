package com.example.smartassistant.common.rag.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetrievalTrace 单元测试。
 */
class RetrievalTraceTest {

    @Test
    @DisplayName("创建 trace 应包含 requestId 和原始 query")
    void constructor_shouldSetRequestIdAndQuery() {
        RetrievalTrace trace = new RetrievalTrace("req-001", "test query");
        assertEquals("req-001", trace.getRequestId());
        assertEquals("test query", trace.getOriginalQuery());
        assertFalse(trace.isHit());
    }

    @Test
    @DisplayName("添加查询变体")
    void addVariant_shouldRecordVariants() {
        RetrievalTrace trace = new RetrievalTrace("req-002", "original");
        trace.addVariant("variant 1").addVariant("variant 2");
        assertEquals(2, trace.getQueryVariants().size());
        assertTrue(trace.getQueryVariants().contains("variant 1"));
    }

    @Test
    @DisplayName("添加检索步")
    void addStep_shouldRecordSteps() {
        RetrievalTrace trace = new RetrievalTrace("req-003", "query");
        trace.addStep("向量检索", "query", 1, "doc-001", "测试文档", 0.95);
        assertEquals(1, trace.getSteps().size());
        assertEquals("doc-001", trace.getSteps().get(0).docId());
    }

    @Test
    @DisplayName("添加 RRF 融合结果")
    void addFused_shouldRecordFusedResults() {
        RetrievalTrace trace = new RetrievalTrace("req-004", "query");
        trace.addFused("doc-001", "测试文档", 0.033, 1);
        assertEquals(1, trace.getFusedResults().size());
    }

    @Test
    @DisplayName("设置命中状态和耗时")
    void hitAndDuration_shouldUpdate() {
        RetrievalTrace trace = new RetrievalTrace("req-005", "query");
        trace.hit(true).durationMs(150);
        assertTrue(trace.isHit());
        assertEquals(150, trace.getTotalDurationMs());
    }

    @Test
    @DisplayName("toSummary 应包含 trace 信息")
    void toSummary_shouldContainTraceInfo() {
        RetrievalTrace trace = new RetrievalTrace("req-006", "test query");
        trace.addStep("path1", "query", 1, "doc-1", "doc1", 0.9);
        trace.hit(true).durationMs(100);
        String summary = trace.toSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("test query"));
        assertTrue(summary.contains("steps=1"));
    }
}
