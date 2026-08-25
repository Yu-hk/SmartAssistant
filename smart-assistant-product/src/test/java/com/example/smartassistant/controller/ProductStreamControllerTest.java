package com.example.smartassistant.controller;

import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.agent.protocol.AgentNodeOutput;
import com.example.smartassistant.common.audit.TokenUsageCache;
import com.example.smartassistant.common.audit.TokenUsageHeaders;
import com.example.smartassistant.common.quality.DomainAgentResponse;
import com.example.smartassistant.common.quality.DomainQualityHeaders;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.service.agent.StreamingProductAgentService;
import com.example.smartassistant.service.core.ProductDiscoveryService;
import com.example.smartassistant.spi.ProductBackend;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductStreamControllerTest {

    @Test
    void streamEmitsMeasuredUsageBeforeDone() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        when(service.execute("耳机推荐")).thenAnswer(ignored -> {
            TokenUsageCache.record("req-stream", 18, 7, 25);
            return "推荐降噪耳机";
        });
        ProductStreamController controller = new ProductStreamController(service);

        List<org.springframework.http.codec.ServerSentEvent<String>> events =
                controller.streamChat("耳机推荐", false, "req-stream")
                        .collectList().block();

        assertEquals(List.of("tool_call", "tool_result", "response", "token_usage", "done"),
                events.stream().map(org.springframework.http.codec.ServerSentEvent::event).toList());
        assertEquals("{\"type\":\"token_usage\",\"promptTokens\":18,"
                        + "\"completionTokens\":7,\"totalTokens\":25}",
                events.get(3).data());
        assertNull(TokenUsageCache.consume("req-stream"));
    }

    @Test
    void syncEndpointKeepsPlainTextBodyAndAddsQualityHeaders() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        when(service.executeWithQuality("推荐办公电脑", "req-1"))
                .thenReturn(DomainAgentResponse.of(
                        "建议选择 16GB 内存的办公电脑。",
                        DomainQualityResult.pass(0.9, "PRODUCT_FACTS_VERIFIED")));
        ProductStreamController controller = new ProductStreamController(service);
        TokenUsageCache.record("req-1", 24, 11, 35);

        var response = controller.chatSync("推荐办公电脑", "req-1");

        assertEquals("建议选择 16GB 内存的办公电脑。", response.getBody());
        assertEquals("PASS", response.getHeaders().getFirst(DomainQualityHeaders.STATUS));
        assertEquals("0.9", response.getHeaders().getFirst(DomainQualityHeaders.SCORE));
        assertEquals("PRODUCT_FACTS_VERIFIED",
                response.getHeaders().getFirst(DomainQualityHeaders.REASON_CODES));
        assertEquals("24", response.getHeaders().getFirst(TokenUsageHeaders.PROMPT_TOKENS));
        assertEquals("11", response.getHeaders().getFirst(TokenUsageHeaders.COMPLETION_TOKENS));
        assertEquals("35", response.getHeaders().getFirst(TokenUsageHeaders.TOTAL_TOKENS));
        verify(service).executeWithQuality("推荐办公电脑", "req-1");
    }

    @Test
    void unifiedEndpointReturnsTypedResponse() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        when(service.executeWithQuality("推荐办公电脑", "req-protocol"))
                .thenReturn(DomainAgentResponse.of("推荐 A 型号",
                        DomainQualityResult.pass(0.92, "PRODUCT_FACTS_VERIFIED")));
        ProductStreamController controller = new ProductStreamController(service);

        var response = controller.execute(AgentExecutionRequest.answer(
                "req-protocol", "1", "推荐办公电脑", null), null);

        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, response.getBody().status());
        assertEquals("推荐 A 型号", response.getBody().answer());
        assertEquals("PASS", response.getBody().quality().status());
    }

    @Test
    void unifiedDiscoveryEndpointReturnsProductsAsStructuredData() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        ProductDiscoveryService discovery = mock(ProductDiscoveryService.class);
        when(discovery.supports("查询热门商品")).thenReturn(true);
        when(discovery.discover("查询热门商品", 3)).thenReturn(
                new ProductDiscoveryService.DiscoveryResult(
                        "1. 降噪耳机（SKU-100） — ¥599", 1, true,
                        List.of(new ProductBackend.ProductSummary(
                                "SKU-100", "降噪耳机", new BigDecimal("599"),
                                "有货", "黑色", 12))));
        ProductStreamController controller = new ProductStreamController(service, discovery);
        AgentExecutionRequest request = new AgentExecutionRequest(
                "1.0", "scene-1", "hot-products", "42", "QUERY_HOT_PRODUCTS",
                "查询热门商品", Map.of("limit", 3), List.of(), List.of(), null,
                null, null);

        var response = controller.execute(request, null);

        assertEquals(1, response.getBody().data().get("productCount"));
        assertEquals("SKU-100", ((ProductBackend.ProductSummary) ((List<?>) response
                .getBody().data().get("products")).getFirst()).code());
        verify(service, org.mockito.Mockito.never()).executeWithQuality(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void scenarioEvidenceBoundaryIsATrustedNonHallucinatingAnswer() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        ProductDiscoveryService discovery = mock(ProductDiscoveryService.class);
        String question = "查询热门商品并判断是否适合视频会议，不要虚构参数";
        when(discovery.supports(question)).thenReturn(true);
        when(discovery.discover(question, null)).thenReturn(
                new ProductDiscoveryService.DiscoveryResult(
                        "目录证据不足，不能把热门等同于适合。", 1, true,
                        List.of(new ProductBackend.ProductSummary(
                                "SKU-100", "候选设备", new BigDecimal("599"),
                                "有货", null, 12)), true));
        ProductStreamController controller = new ProductStreamController(service, discovery);

        var response = controller.execute(AgentExecutionRequest.answer(
                "scene-limited", "42", question, null), null);

        assertEquals("PASS", response.getBody().quality().status());
        assertEquals(List.of("PRODUCT_SCENARIO_EVIDENCE_LIMITED"),
                response.getBody().quality().reasonCodes());
    }

    @Test
    void analysisNodeConsumesOnlyDeclaredPredecessorOutputs() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        ProductStreamController controller = new ProductStreamController(service);
        Map<String, AgentNodeOutput> predecessors = Map.of(
                "discover_products", new AgentNodeOutput(
                        "discover_products", "product", "SUCCEEDED",
                        "候选商品：降噪耳机 SKU-100，¥599，有货",
                        Map.of("products", List.of(Map.of(
                                "code", "SKU-100", "name", "降噪耳机", "price", 599)))));
        AgentExecutionRequest request = new AgentExecutionRequest(
                "1.0", "scene-analysis", "analyze_product_data", "42",
                "ANALYZE_PRODUCT_DATA", "分析候选商品与用户预算的匹配度",
                Map.of(), List.of("discover_products"), List.of(), null,
                null, null, predecessors, "shopping", 1, "sha256:v1", 0,
                "scene-analysis");
        when(service.analyzeVerifiedContext(
                org.mockito.ArgumentMatchers.eq("分析候选商品与用户预算的匹配度"),
                org.mockito.ArgumentMatchers.contains("SKU-100"),
                org.mockito.ArgumentMatchers.eq("scene-analysis")))
                .thenReturn(DomainAgentResponse.of(
                        "### 数据分析开始 ###\n【核心结论】SKU-100 符合预算",
                        DomainQualityResult.pass(1.0, "VERIFIED_PRODUCT_ANALYSIS")));

        var response = controller.execute(request, null);

        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, response.getBody().status());
        assertEquals(List.of("discover_products"), response.getBody().data().get("sourceNodeIds"));
        assertEquals("### 数据分析开始 ###\n【核心结论】SKU-100 符合预算",
                response.getBody().data().get("analysis"));
        verify(service).analyzeVerifiedContext(
                org.mockito.ArgumentMatchers.eq("分析候选商品与用户预算的匹配度"),
                org.mockito.ArgumentMatchers.argThat(context ->
                        context.contains("SKU-100") && context.contains("结构化数据")
                                && !context.contains("候选商品：")),
                org.mockito.ArgumentMatchers.eq("scene-analysis"));
    }

    @Test
    void recommendationNodePublishesStableStructuredRecommendation() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        ProductStreamController controller = new ProductStreamController(service);
        Map<String, AgentNodeOutput> predecessors = Map.of(
                "analyze_product_data", new AgentNodeOutput(
                        "analyze_product_data", "product", "SUCCEEDED",
                        "SKU-100 符合预算", Map.of("analysis", "SKU-100 符合预算")));
        AgentExecutionRequest request = new AgentExecutionRequest(
                "1.0", "scene-recommend", "recommend_product", "42",
                "RECOMMEND_PRODUCT", "核实分析并推荐",
                Map.of(), List.of("analyze_product_data"), List.of(), null,
                null, null, predecessors, "shopping", 1, "sha256:v1", 0,
                "scene-recommend");
        when(service.verifyAnalysisAndRecommend(
                org.mockito.ArgumentMatchers.eq("核实分析并推荐"),
                org.mockito.ArgumentMatchers.contains("SKU-100"),
                org.mockito.ArgumentMatchers.eq("scene-recommend")))
                .thenReturn(DomainAgentResponse.of(
                        "推荐 SKU-100，价格 ¥599，库存有货",
                        DomainQualityResult.pass(1.0, "PRODUCT_RECOMMENDATION_PRO_VERIFIED")));

        var response = controller.execute(request, null);

        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, response.getBody().status());
        assertEquals("推荐 SKU-100，价格 ¥599，库存有货",
                response.getBody().data().get("recommendation"));
    }
}
