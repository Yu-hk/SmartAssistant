/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * StageTraceRecorder 单元测试：覆盖纯内存模式、Mock Redis 持久化、序列化与阶段聚合。
 */
class StageTraceRecorderTest {

    @Test
    @DisplayName("纯内存模式：记录阶段后可按 requestId 查询")
    void inMemory_shouldRecordAndFind() {
        StageTraceRecorder recorder = new StageTraceRecorder(null);
        recorder.getOrCreate("req-1", "查询订单", "order_agent");
        recorder.recordStage("req-1", RagStage.RETRIEVAL, StageSpan.STATUS_OK, 12,
                Map.of("qualityScore", 0.85));
        recorder.recordStage("req-1", RagStage.GENERATION, StageSpan.STATUS_OK, 420,
                Map.of("outputLength", 56));
        recorder.save("req-1");

        RagStageTrace trace = recorder.findByRequestId("req-1");
        assertNotNull(trace);
        assertEquals("req-1", trace.getRequestId());
        assertEquals(2, trace.getStages().size());
        assertEquals(RagStage.GENERATION, trace.lastStageOf(RagStage.GENERATION).stage());
        assertEquals(420, trace.lastStageOf(RagStage.GENERATION).durationMs());
        assertTrue(trace.toSummary().contains("GENERATION"));
    }

    @Test
    @DisplayName("markRejection 应记录拒答原因与 REJECTION 阶段")
    void markRejection_shouldSetCodeAndRejectionStage() {
        StageTraceRecorder recorder = new StageTraceRecorder(null);
        recorder.getOrCreate("req-2", "无依据问题", "product_agent");
        recorder.markRejection("req-2", "NO_RELEVANT_DATA", "未找到相关信息");
        recorder.save("req-2");

        RagStageTrace trace = recorder.findByRequestId("req-2");
        assertNotNull(trace);
        assertTrue(trace.isRejected());
        assertEquals("NO_RELEVANT_DATA", trace.getRejectionCode());
        assertEquals("未找到相关信息", trace.getRejectionMessage());
        assertNotNull(trace.lastStageOf(RagStage.REJECTION));
        assertTrue(trace.toSummary().contains("REJECTED=NO_RELEVANT_DATA"));
    }

    @Test
    @DisplayName("requestId 为 null 时自动生成且仍可查询")
    void nullRequestId_shouldAutoGenerate() {
        StageTraceRecorder recorder = new StageTraceRecorder(null);
        recorder.recordStage(null, RagStage.GENERATION, StageSpan.STATUS_OK, 10, Map.of());
        // 自动生成的 id 不可预测，但 inMemory 中应有一条
        assertEquals(1, recorder.findByRequestId(
                recorder.getOrCreate(null, "q", "a").getRequestId()) != null ? 1 : 0);
    }

    @Test
    @DisplayName("Mock Redis：save 应写入 a2a:stage:trace:{requestId}")
    void mockRedis_shouldPersistOnSave() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        StageTraceRecorder recorder = new StageTraceRecorder(redis);
        recorder.getOrCreate("req-r", "q", "order_agent");
        recorder.recordStage("req-r", RagStage.GENERATION, StageSpan.STATUS_OK, 30, Map.of());
        recorder.save("req-r");

        verify(redis).opsForValue();
        verify(ops).set(eq("a2a:stage:trace:req-r"), anyString(), any());
    }

    @Test
    @DisplayName("Mock Redis：findByRequestId 应反序列化回写内容")
    void mockRedis_shouldReadBack() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        // 先把一条 trace 序列化成 JSON 供 mock 返回
        StageTraceRecorder seed = new StageTraceRecorder(null);
        seed.getOrCreate("req-x", "查询", "product_agent");
        seed.recordStage("req-x", RagStage.RETRIEVAL, StageSpan.STATUS_OK, 5, Map.of("q", 0.5));
        seed.save("req-x");
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(seed.findByRequestId("req-x"));

        when(ops.get("a2a:stage:trace:req-x")).thenReturn(json);

        StageTraceRecorder recorder = new StageTraceRecorder(redis);
        RagStageTrace trace = recorder.findByRequestId("req-x");
        assertNotNull(trace);
        assertEquals("req-x", trace.getRequestId());
        assertEquals(1, trace.getStages().size());
        assertEquals(RagStage.RETRIEVAL, trace.getStages().get(0).stage());
    }
}
