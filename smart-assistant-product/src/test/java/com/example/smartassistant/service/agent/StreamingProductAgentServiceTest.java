/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.agent;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.common.model.tier.ModelTier;
import com.example.smartassistant.common.model.tier.TierModelRegistry;
import com.example.smartassistant.common.rag.trace.RagStage;
import com.example.smartassistant.common.rag.trace.StageTraceRecorder;
import com.example.smartassistant.service.search.ProductRagService;
import com.example.smartassistant.service.core.ProductDiscoveryService;
import com.example.smartassistant.service.quality.ProductDomainQualityValidator;
import com.example.smartassistant.spi.InMemoryProductBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
    @DisplayName("热门商品：应查询真实商品目录，不经过 RAG 拒答或 LLM")
    void popularProducts_shouldUseDeterministicDiscovery() {
        StreamingProductAgentService discoveryService = new StreamingProductAgentService(
                agent, ragService, new ProductDomainQualityValidator(),
                new ProductDiscoveryService(new InMemoryProductBackend()));

        String result = discoveryService.execute("现在有什么热门商品", "req-p-popular");

        assertTrue(result.contains("当前推荐商品"));
        assertTrue(result.contains("AirPods Pro"));
        assertFalse(result.contains("数据库中未找到"));
        verifyNoInteractions(ragService);
        verify(agent, never()).execute(anyString());
    }

    @Test
    @DisplayName("商品推荐直连入口：也应经过 Flash 分析和 Pro 核实")
    void popularProducts_shouldUseDualModelWorkflowWithCatalogContext() {
        StreamingProductAgentService discoveryService = new StreamingProductAgentService(
                agent, ragService, new ProductDomainQualityValidator(),
                new ProductDiscoveryService(new InMemoryProductBackend()));
        org.springframework.ai.chat.model.ChatModel flash =
                mock(org.springframework.ai.chat.model.ChatModel.class);
        org.springframework.ai.chat.model.ChatModel pro =
                mock(org.springframework.ai.chat.model.ChatModel.class);
        discoveryService.setPromptManager(new PromptManager());
        discoveryService.setTierModelRegistry(tierRegistry(flash, pro));
        when(flash.call(any(Prompt.class))).thenReturn(chatResponse(
                "### 数据分析开始 ###\n【核心结论】AIRPODS-PRO，¥1999，库存充足"));
        when(pro.call(any(Prompt.class))).thenReturn(
                chatResponse("{\"valid\":true,\"issues\":[],\"correction_instruction\":\"\"}"),
                chatResponse("### 推荐核实完成 ###\n推荐 AIRPODS-PRO（AirPods Pro（第二代）），"
                        + "价格 ¥1999，库存充足，请确认后下单。"));

        String result = discoveryService.execute("推荐几款热门商品", "req-p-analysis");

        assertTrue(result.startsWith("### 推荐核实完成 ###"));
        verifyNoInteractions(ragService);
        org.mockito.ArgumentCaptor<Prompt> directAnalysisPrompt =
                org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(flash).call(directAnalysisPrompt.capture());
        assertTrue(directAnalysisPrompt.getValue().getContents().contains("推荐几款热门商品"));
        assertTrue(directAnalysisPrompt.getValue().getContents().contains("AirPods Pro"));
        assertTrue(directAnalysisPrompt.getValue().getContents().contains("【核心结论】"));
        assertEquals(900, directAnalysisPrompt.getValue().getOptions().getMaxTokens());
        verify(pro, times(2)).call(any(Prompt.class));
        verify(agent, never()).execute(anyString());
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

    @Test
    @DisplayName("分析节点：必须只调用 Flash 模型")
    void analysisNode_shouldUseFlashModelOnly() {
        org.springframework.ai.chat.model.ChatModel flash =
                mock(org.springframework.ai.chat.model.ChatModel.class);
        org.springframework.ai.chat.model.ChatModel pro =
                mock(org.springframework.ai.chat.model.ChatModel.class);
        when(flash.call(any(Prompt.class))).thenReturn(chatResponse(
                "### 数据分析开始 ###\n【核心结论】SKU-100，¥599，有货"));
        StreamingProductAgentService dualModelService = dualModelService(flash, pro);

        var result = dualModelService.analyzeVerifiedContext(
                "分析预算匹配度", "SKU-100，¥599，有货", "req-flash-analysis");

        assertTrue(result.answer().contains("SKU-100"));
        assertTrue(result.quality().getReasonCodes().contains("PRODUCT_ANALYSIS_FLASH"));
        org.mockito.ArgumentCaptor<Prompt> analysisPrompt =
                org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(flash).call(analysisPrompt.capture());
        assertTrue(analysisPrompt.getValue().getContents().contains("分析预算匹配度"));
        assertTrue(analysisPrompt.getValue().getContents().contains("SKU-100"));
        assertEquals(900, analysisPrompt.getValue().getOptions().getMaxTokens());
        assertEquals("qwen3.7-flash", analysisPrompt.getValue().getOptions().getModel());
        verifyNoInteractions(pro);
    }

    @Test
    @DisplayName("推荐节点：Pro 否决后应让 Flash 重分析并由 Pro 再核实")
    void recommendationNode_shouldReanalyzeWhenProRejectsAnalysis() {
        org.springframework.ai.chat.model.ChatModel flash =
                mock(org.springframework.ai.chat.model.ChatModel.class);
        org.springframework.ai.chat.model.ChatModel pro =
                mock(org.springframework.ai.chat.model.ChatModel.class);
        when(flash.call(any(Prompt.class))).thenReturn(chatResponse(
                "修正分析：SKU-100 价格 ¥599、库存有货；无场景规格证据"));
        when(pro.call(any(Prompt.class))).thenReturn(
                chatResponse("{\"valid\":false,\"issues\":[\"错误声称支持会议\"],"
                        + "\"correction_instruction\":\"删除无证据的会议适配结论\"}"),
                chatResponse("{\"valid\":true,\"issues\":[],\"correction_instruction\":\"\"}"),
                chatResponse("### 推荐核实完成 ###\n推荐 SKU-100，价格 ¥599，库存有货；"
                        + "现有数据不支持会议适配结论，请确认后再下单。"));
        StreamingProductAgentService dualModelService = dualModelService(flash, pro);
        dualModelService.setMaxReanalysis(1);

        var result = dualModelService.verifyAnalysisAndRecommend(
                "推荐适合会议的耳机",
                "候选：SKU-100，价格 ¥599，库存有货。\n分析：SKU-100 完全支持会议。",
                "req-pro-audit");

        assertTrue(result.answer().startsWith("### 推荐核实完成 ###"));
        assertTrue(result.quality().getReasonCodes().contains(
                "PRODUCT_RECOMMENDATION_PRO_VERIFIED_AFTER_REANALYSIS"));
        org.mockito.ArgumentCaptor<Prompt> correctionPrompt =
                org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(flash).call(correctionPrompt.capture());
        assertTrue(correctionPrompt.getValue().getContents().contains("删除无证据的会议适配结论"));
        verify(pro, times(3)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("推荐节点：Pro 首次未返回 JSON 时只重试核实格式，不重跑 Flash")
    void recommendationNode_shouldRetryInvalidAuditFormatWithoutReanalysis() {
        org.springframework.ai.chat.model.ChatModel flash =
                mock(org.springframework.ai.chat.model.ChatModel.class);
        org.springframework.ai.chat.model.ChatModel pro =
                mock(org.springframework.ai.chat.model.ChatModel.class);
        when(pro.call(any(Prompt.class))).thenReturn(
                chatResponse("分析内容没有问题，可以继续推荐。"),
                chatResponse("```json\n{\"valid\":true,\"issues\":[],"
                        + "\"correction_instruction\":\"\"}\n```"),
                chatResponse("### 推荐核实完成 ###\n推荐 XIAOMI-15（小米 15 Pro），"
                        + "价格 ¥5299，库存充足，规格含徕卡光学；请确认后下单。"));
        StreamingProductAgentService dualModelService = dualModelService(flash, pro);

        var result = dualModelService.verifyAnalysisAndRecommend(
                "预算6000元以内并重视拍照",
                "候选：XIAOMI-15，小米 15 Pro，价格 ¥5299，库存充足，规格：徕卡光学。"
                        + "\n分析：预算满足，拍照偏好有徕卡光学规格证据。",
                "req-pro-format-retry");

        assertTrue(result.answer().startsWith("### 推荐核实完成 ###"));
        assertTrue(result.quality().isPass());
        verify(flash, never()).call(any(Prompt.class));
        org.mockito.ArgumentCaptor<Prompt> prompts =
                org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(pro, times(3)).call(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).getContents().contains("上一次输出未能解析"));
    }

    private StreamingProductAgentService dualModelService(
            org.springframework.ai.chat.model.ChatModel flash,
            org.springframework.ai.chat.model.ChatModel pro) {
        StreamingProductAgentService configured = new StreamingProductAgentService(agent, ragService);
        configured.setPromptManager(new PromptManager());
        configured.setTierModelRegistry(tierRegistry(flash, pro));
        return configured;
    }

    private TierModelRegistry tierRegistry(
            org.springframework.ai.chat.model.ChatModel flash,
            org.springframework.ai.chat.model.ChatModel pro) {
        Map<ModelTier, TierModelRegistry.TierModelEntry> entries = new EnumMap<>(ModelTier.class);
        entries.put(ModelTier.LIGHT,
                new TierModelRegistry.TierModelEntry(flash, "qwen3.7-flash"));
        entries.put(ModelTier.STANDARD,
                new TierModelRegistry.TierModelEntry(flash, "qwen3.7-flash"));
        entries.put(ModelTier.HEAVY,
                new TierModelRegistry.TierModelEntry(pro, "qwen3.7-plus"));
        return new TierModelRegistry(entries);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
