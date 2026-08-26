/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.memory.ContextOrchestrator;
import com.example.smartassistant.common.memory.MemoryExtractor;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.quality.DomainQualityHeaders;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.trace.RagStage;
import com.example.smartassistant.common.rag.trace.StageTraceRecorder;
import com.example.smartassistant.service.core.OrderIntentService;
import com.example.smartassistant.service.core.OrderIntentService.IntentType;
import com.example.smartassistant.service.core.OrderDeterministicExecutionService;
import com.example.smartassistant.service.core.OrderRagService;
import com.example.smartassistant.service.quality.OrderDomainQualityValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

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

        ResponseEntity<String> response = controller.processQuestionHttp(
                Map.of("question", "查询订单 ORD-999 的状态", "requestId", "req-reject"));
        String result = response.getBody();

        assertNotNull(result);
        assertTrue(result.contains("ORD-999"), "应返回结构化拒答消息");
        assertEquals("PASS", response.getHeaders().getFirst(DomainQualityHeaders.STATUS));
        assertEquals("SAFE_NO_EVIDENCE_RESPONSE",
                response.getHeaders().getFirst(DomainQualityHeaders.REASON_CODES));
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

    @Test
    @DisplayName("退款政策：直接返回公共知识，不进入需要确认的订单操作循环")
    void refundPolicy_shouldReturnKnowledgeWithoutCallingAgent() {
        RetrievalQualityResult policy = RetrievalQualityResult.highQuality(
                "【退款与退货政策】\n商品签收后7天内，商品完好且附件齐全可申请退货。", 0.95);
        when(intentService.detect(anyString())).thenReturn(IntentType.REFUND_POLICY);
        when(ragService.retrieveWithQualityResult(eq(IntentType.REFUND_POLICY), anyString()))
                .thenReturn(policy);
        when(ragService.buildRefundPolicyAnswer(policy))
                .thenReturn("根据当前退货退款政策：\n商品签收后7天内，商品完好且附件齐全可申请退货。");

        String result = controller.processQuestion(Map.of(
                "question", "商品退货退款需要满足哪些条件？",
                "requestId", "req-refund-policy"));

        assertTrue(result.contains("7天内"));
        assertTrue(result.contains("商品完好"));
        verify(agent, never()).execute(anyString());
        assertEquals("SKIPPED", recorder.findByRequestId("req-refund-policy")
                .lastStageOf(RagStage.GENERATION).status());
    }

    @Test
    @DisplayName("订单生命周期说明：直接返回只读指南，不进入执行型 Agent")
    void orderGuidance_shouldReturnReadOnlyGuideWithoutCallingAgent() {
        RetrievalQualityResult guidance = RetrievalQualityResult.highQuality(
                "【订单生命周期操作说明】\n查询订单、取消订单和申请售后均需先确认目标订单。", 1.0);
        when(intentService.detect(anyString())).thenReturn(IntentType.ORDER_GUIDANCE);
        when(ragService.retrieveWithQualityResult(eq(IntentType.ORDER_GUIDANCE), anyString()))
                .thenReturn(guidance);
        when(ragService.buildOrderGuidanceAnswer(guidance))
                .thenReturn(guidance.getContent());

        String result = controller.processQuestion(Map.of(
                "question", "请说明下单后如何查询、取消和申请售后",
                "requestId", "req-order-guidance"));

        assertTrue(result.contains("查询订单"));
        assertTrue(result.contains("取消订单"));
        assertTrue(result.contains("申请售后"));
        verify(agent, never()).execute(anyString());
        assertEquals("SKIPPED", recorder.findByRequestId("req-order-guidance")
                .lastStageOf(RagStage.GENERATION).status());
    }

    @Test
    @DisplayName("下单前资料说明：直接返回清单，不进入执行型 Agent")
    void orderPreparation_shouldReturnChecklistWithoutCallingAgent() {
        RetrievalQualityResult guidance = RetrievalQualityResult.highQuality(
                "【下单前信息清单】\n商品、数量、收货人姓名；当前不会执行下单，也不会创建测试订单。", 1.0);
        when(intentService.detect(anyString())).thenReturn(IntentType.ORDER_PREPARATION_GUIDANCE);
        when(ragService.retrieveWithQualityResult(eq(IntentType.ORDER_PREPARATION_GUIDANCE), anyString()))
                .thenReturn(guidance);
        when(ragService.buildOrderGuidanceAnswer(guidance)).thenReturn(guidance.getContent());

        String result = controller.processQuestion(Map.of(
                "question", "说明创建订单前需要确认哪些信息，但不要执行下单",
                "requestId", "req-order-preparation"));

        assertTrue(result.contains("收货人姓名"));
        assertTrue(result.contains("不会创建测试订单"));
        verify(agent, never()).execute(anyString());
    }

    @Test
    @DisplayName("统一协议：返回类型化订单响应")
    void unifiedProtocol_shouldReturnTypedResponse() {
        when(ragService.retrieveWithQualityResult(any(), anyString()))
                .thenReturn(RetrievalQualityResult.highQuality("【订单信息】ORD-123 已发货", 0.9));
        when(agent.execute(anyString())).thenReturn("订单已发货");

        var response = controller.execute(AgentExecutionRequest.answer(
                "req-order-protocol", "1", "查询订单 ORD-123", null), null);

        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, response.getBody().status());
        assertTrue(response.getBody().answer().contains("已发货"));
    }

    @Test
    @DisplayName("统一协议快速路径：确定性订单查询不应进入意图识别、RAG 或 Agent")
    void unifiedProtocol_deterministicQueryShouldBypassModelPipeline() {
        OrderDeterministicExecutionService deterministic =
                mock(OrderDeterministicExecutionService.class);
        OrderAgentController fastController = new OrderAgentController(
                agent, intentService, ragService, mock(MemoryExtractor.class), orchestrator,
                new OrderDomainQualityValidator(), deterministic);
        AgentExecutionRequest request = new AgentExecutionRequest(
                AgentExecutionRequest.CURRENT_VERSION, "req-fast-order", "query-order", "1",
                "QUERY_ORDER", "查询订单 ORD-123", Map.of("orderId", "ORD-123"),
                List.of(), List.of(), null, "idem-fast-order", null);
        AgentExecutionResponse result = AgentExecutionResponse.success(
                "订单 ORD-123 状态：待发货",
                Map.of("orderId", "ORD-123", "verified", true, "criteriaSatisfied", true),
                DomainQualityResult.pass(1.0, "DETERMINISTIC_ORDER_QUERY"));
        when(deterministic.supports("QUERY_ORDER")).thenReturn(true);
        when(deterministic.execute(request)).thenReturn(result);

        ResponseEntity<AgentExecutionResponse> response = fastController.execute(request, null);

        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, response.getBody().status());
        assertEquals(true, response.getBody().data().get("verified"));
        assertEquals("PASS", response.getHeaders().getFirst(DomainQualityHeaders.STATUS));
        verify(deterministic).execute(request);
        verifyNoInteractions(intentService, ragService);
        verify(agent, never()).execute(anyString());
    }
}
