/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.memory.ContextOrchestrator;
import com.example.smartassistant.common.memory.MemoryExtractor;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.trace.RagStage;
import com.example.smartassistant.common.rag.trace.StageTraceRecorder;
import com.example.smartassistant.service.core.OrderIntentService;
import com.example.smartassistant.service.core.OrderIntentService.IntentType;
import com.example.smartassistant.service.core.OrderRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderAgentController P1 行为测试（纯 Mockito，不加载 Spring 上下文）。
 * 验证：无证据拒答短路（不调用 LLM）与正常路径注入上下文 + 全阶段 trace 记录。
 */
class OrderAgentControllerTest {

    private SmartReActAgent agent;
    private OrderIntentService intentService;
    private OrderRagService ragService;
    private ContextOrchestrator orchestrator;
    private OrderAgentController controller;
    private StageTraceRecorder recorder;

    @BeforeEach
    void setUp() {
        agent = mock(SmartReActAgent.class);
        intentService = mock(OrderIntentService.class);
        ragService = mock(OrderRagService.class);
        orchestrator = mock(ContextOrchestrator.class);
        MemoryExtractor memoryExtractor = mock(MemoryExtractor.class);
        controller = new OrderAgentController(agent, intentService, ragService, memoryExtractor, orchestrator);
        recorder = new StageTraceRecorder(null);
        controller.setStageTraceRecorder(recorder);

        when(intentService.detect(anyString())).thenReturn(IntentType.QUERY_ORDER);
        // buildEnhancedMessage 调用真实实现（mock 默认返回 null 会触发 NPE）
        when(ragService.buildEnhancedMessage(any(com.example.smartassistant.common.rag.RetrievalQualityResult.class), anyString()))
                .thenCallRealMethod();
        // orchestrator 将 extras（RAG 上下文）拼回 prompt，便于断言上下文已注入 Agent
        when(orchestrator.buildPrompt(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            String question = i.getArgument(0);
            List<String> extras = i.getArgument(3);
            return (extras == null ? "" : String.join("\n", extras)) + "\n" + question;
        });
    }

    @Test
    @DisplayName("无证据拒答：检索被拒时应短路返回拒答消息且不调用 LLM")
    void noEvidence_shouldRejectWithoutCallingAgent() {
        when(ragService.retrieveWithQualityResult(any(), anyString()))
                .thenReturn(RetrievalQualityResult.noData("ORD-999"));

        String result = controller.processQuestion(
                Map.of("question", "查询订单 ORD-999 的状态", "requestId", "req-reject"));

        assertNotNull(result);
        assertTrue(result.contains("ORD-999"), "应返回结构化拒答消息");
        verify(agent, never()).execute(anyString());

        // trace 应记录 REJECTION + GENERATION(SKIPPED)
        var trace = recorder.findByRequestId("req-reject");
        assertNotNull(trace);
        assertTrue(trace.isRejected());
        assertNotNull(trace.lastStageOf(RagStage.REJECTION));
        assertEquals("SKIPPED", trace.lastStageOf(RagStage.GENERATION).status());
    }

    @Test
    @DisplayName("正常路径：有证据时应注入上下文并调用 LLM，且记录 GENERATION(OK)")
    void normalPath_shouldInjectContextAndCallAgent() {
        when(ragService.retrieveWithQualityResult(any(), anyString()))
                .thenReturn(RetrievalQualityResult.highQuality("【订单信息】ORD-123 已发货", 0.9));
        when(agent.execute(anyString())).thenReturn("已为您查询到订单状态");

        String result = controller.processQuestion(
                Map.of("question", "查询订单 ORD-123", "requestId", "req-ok"));

        assertNotNull(result);
        verify(agent, times(1)).execute(argThat(msg -> msg.contains("系统已检索到以下信息")
                && msg.contains("ORD-123")));

        var trace = recorder.findByRequestId("req-ok");
        assertNotNull(trace);
        assertFalse(trace.isRejected());
        assertEquals("OK", trace.lastStageOf(RagStage.GENERATION).status());
        assertTrue(trace.lastStageOf(RagStage.GENERATION).durationMs() >= 0);
    }
}
