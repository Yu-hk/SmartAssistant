/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.agent;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.trace.RagStage;
import com.example.smartassistant.common.rag.trace.StageTraceRecorder;
import com.example.smartassistant.service.search.ProductRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StreamingProductAgentService P1 行为测试（纯 Mockito）。
 * 验证：无证据拒答短路、高质量注入上下文、RAG 异常降级。
 */
class StreamingProductAgentServiceTest {

    private SmartReActAgent agent;
    private ProductRagService ragService;
    private StreamingProductAgentService service;
    private StageTraceRecorder recorder;

    @BeforeEach
    void setUp() {
        agent = mock(SmartReActAgent.class);
        ragService = mock(ProductRagService.class);
        service = new StreamingProductAgentService(agent, ragService);
        recorder = new StageTraceRecorder(null);
        service.setStageTraceRecorder(recorder);
        when(agent.execute(anyString())).thenReturn("商品咨询答复");
    }

    @Test
    @DisplayName("无证据拒答：RAG 拒绝时应返回拒答消息且不调用 LLM")
    void noEvidence_shouldRejectWithoutCallingAgent() {
        when(ragService.retrieveWithQualityResult(anyString()))
                .thenReturn(RetrievalQualityResult.noData("无线耳机"));

        String result = service.execute("推荐无线耳机", "req-p-reject");

        assertNotNull(result);
        assertTrue(result.contains("无线耳机"), "应返回结构化拒答消息");
        verify(agent, never()).execute(anyString());

        var trace = recorder.findByRequestId("req-p-reject");
        assertNotNull(trace);
        assertTrue(trace.isRejected());
        assertNotNull(trace.lastStageOf(RagStage.REJECTION));
        assertEquals("SKIPPED", trace.lastStageOf(RagStage.GENERATION).status());
    }

    @Test
    @DisplayName("高质量：应把检索知识注入上下文再调用 LLM")
    void highQuality_shouldInjectContext() {
        when(ragService.retrieveWithQualityResult(anyString()))
                .thenReturn(RetrievalQualityResult.highQuality("【商品检索结果】iPhone 15", 0.92));
        when(agent.execute(anyString())).thenReturn("iPhone 15 详情如下");

        String result = service.execute("iPhone 15 怎么样", "req-p-ok");

        assertNotNull(result);
        verify(agent, times(1)).execute(argThat(msg ->
                msg.contains("系统已检索到以下商品信息") && msg.contains("iPhone 15")));

        var trace = recorder.findByRequestId("req-p-ok");
        assertNotNull(trace);
        assertFalse(trace.isRejected());
        assertEquals("OK", trace.lastStageOf(RagStage.GENERATION).status());
    }

    @Test
    @DisplayName("RAG 检索异常：应降级为无上下文直接生成，不阻断主流程")
    void ragFailure_shouldFallbackToNoContext() {
        when(ragService.retrieveWithQualityResult(anyString())).thenThrow(new RuntimeException("embedding down"));

        String result = service.execute("任意商品咨询", "req-p-fallback");

        assertNotNull(result);
        // 异常降级：仍调用 LLM，且传入的是原始问题（无注入上下文）
        verify(agent, times(1)).execute(eq("任意商品咨询"));
        var trace = recorder.findByRequestId("req-p-fallback");
        assertNotNull(trace);
        assertFalse(trace.isRejected());
    }

    @Test
    @DisplayName("无 productRagService 时：保持纯 LLM 行为（向后兼容）")
    void noRagService_shouldCallAgentDirectly() {
        StreamingProductAgentService legacy = new StreamingProductAgentService(agent, null);
        String result = legacy.execute("你好", "req-p-legacy");
        assertNotNull(result);
        verify(agent, times(1)).execute(eq("你好"));
    }
}
