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
import com.example.smartassistant.routing.contract.RoutingKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductStreamControllerTest {

    @Test
    void modelIsPreferredAndOnlyModelFailureEnablesFactFallback() {
        var agent = mock(StreamingProductAgentService.class);
        var facts = mock(com.example.smartassistant.service.core.ProductFactQueryService.class);
        var controller = new ProductStreamController(agent);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "factQueryService", facts);
        var request = new AgentExecutionRequest("1.0", "model-first", "query", "12", "QUERY_PRODUCT",
                "AirPods Pro多少钱？有货吗？", Map.of(), List.of(), List.of(), null, null);
        when(agent.executeWithQuality(anyString(), eq("model-first"))).thenReturn(
                DomainAgentResponse.of("已为您查询：售价1999元，有货。", DomainQualityResult.pass(1, "VERIFIED")));
        assertEquals("已为您查询：售价1999元，有货。", controller.execute(request, null).getBody().answer());
        org.mockito.Mockito.verifyNoInteractions(facts);
        when(agent.executeWithQuality(anyString(), eq("model-first"))).thenReturn(
                DomainAgentResponse.of("服务不可用", DomainQualityResult.fail("MODEL_BILLING_UNAVAILABLE")));
        when(facts.query(anyString(), anyList(), eq("model-first"))).thenReturn(
                AgentExecutionResponse.success("售价1999元，库存充足。", Map.of("handled", true), DomainQualityResult.pass(1, "FACT")));
        assertEquals("售价1999元，库存充足。", controller.execute(request, null).getBody().answer());
        verify(facts).query(anyString(), anyList(), eq("model-first"));
    }

    @Test
    void exposesBudgetAssessmentWithoutUnrequestedRemainder() {
        var response = recommendationWith(List.of(Map.of("code", "PHONE", "name", "手机", "price", 5299)),
                "可考虑手机，售价5299元，未超预算。", "预算6000元以内推荐手机");
        var budget = (Map<?, ?>) response.data().get("budgetAssessment");
        assertEquals(false, budget.get("detailsRequested"));
        var row = (Map<?, ?>) ((List<?>) budget.get("products")).getFirst();
        assertEquals("WITHIN_BUDGET", row.get("status"));
        org.junit.jupiter.api.Assertions.assertFalse(row.containsKey("remainder"));
    }

    @Test
    void noEligibleProductsAreNotReplacedByRecommendationFallback() {
        var service = new StreamingProductAgentService(null, null,
                new com.example.smartassistant.service.quality.ProductDomainQualityValidator(), null);
        var upstream = new AgentNodeOutput("discover", "product", "SUCCEEDED", "目录", Map.of("products",
                List.of(Map.of("code", "EXPENSIVE", "name", "高价手机", "price", 6999))));
        var http = new ProductStreamController(service).execute(
                productStep("RECOMMEND_PRODUCT", "预算6000元", upstream), null);
        assertEquals("0", http.getHeaders().getFirst(TokenUsageHeaders.TOTAL_TOKENS));
        var result = http.getBody();
        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, result.status());
        assertEquals(List.of("NO_ELIGIBLE_VERIFIED_PRODUCT"), result.quality().reasonCodes());
        org.assertj.core.api.Assertions.assertThat(result.answer()).contains("超出预算").doesNotContain("优先考虑", "差额");
    }

    @Test
    void conflictingOrFailedCatalogIsNotTreatedAsVerifiedFacts() {
        var service = new StreamingProductAgentService(null, null,
                new com.example.smartassistant.service.quality.ProductDomainQualityValidator(), null);
        var controller = new ProductStreamController(service);
        var failed = new AgentNodeOutput("discover", "product", "FAILED", "失败", Map.of("products",
                List.of(Map.of("code", "PHONE", "name", "手机", "price", 5299))));
        assertEquals(AgentExecutionResponse.Status.FAILED, controller.execute(
                productStep("RECOMMEND_PRODUCT", "预算6000元", failed), null).getBody().status());
        var conflict = new AgentNodeOutput("discover", "product", "SUCCEEDED", "目录冲突", Map.of("products", List.of(
                Map.of("code", "PHONE", "name", "手机", "price", 5299),
                Map.of("code", "PHONE", "name", "手机", "price", 6999))));
        assertEquals(AgentExecutionResponse.Status.FAILED, controller.execute(
                productStep("RECOMMEND_PRODUCT", "预算6000元", conflict), null).getBody().status());
    }

    @Test
    void singlePhoneFallbackDoesNotForgetBudgetOrInventTiesAndMissingReviews() {
        var result = recommendationWith(List.of(Map.of(
                "code", "XIAOMI-15", "name", "小米 15 Pro", "price", 5299,
                "stock", "充足", "spec", "徕卡光学", "popularity", 1982,
                "rating", 4.8, "reviewCount", 8620)), "暂时无法选出唯一商品",
                "预算6000元以内并重视拍照，请推荐一款手机");
        org.assertj.core.api.Assertions.assertThat(result.answer())
                .contains("5299", "小米 15 Pro", "徕卡光学", "4.8/5", "8620", "具体用途")
                .doesNotContain("并列", "均为", "尚未提供", "告诉我预算", "暂无口碑", "近期订单");
        assertEquals("WARN", result.quality().status());
    }

    @Test
    void differingOrUnknownSalesAreNeverReportedAsTies() {
        for (Map<String, Object> second : List.of(
                Map.<String, Object>of("code", "B", "name", "手机B", "price", 2000, "popularity", 6),
                Map.<String, Object>of("code", "B", "name", "手机B", "price", 2000))) {
            var result = recommendationWith(List.of(
                    Map.of("code", "A", "name", "手机A", "price", 1000, "popularity", 3), second),
                    "数据尚不足以选出唯一商品", "推荐手机");
            org.assertj.core.api.Assertions.assertThat(result.answer())
                    .doesNotContain("并列", "均为", "尚未提供");
        }
    }

    @Test
    void zeroSalesAreNotEvidenceOfPopularity() {
        var result = recommendationWith(List.of(
                Map.of("code", "A", "name", "手机A", "price", 1000, "popularity", 0),
                Map.of("code", "B", "name", "手机B", "price", 2000, "popularity", 0)),
                "没有足够证据推荐", "推荐手机");
        org.assertj.core.api.Assertions.assertThat(result.answer()).doesNotContain("均为", "热门", "销量：0");
    }

    @Test
    void validRecommendationSurvivesWhitespaceAndCaseVariationsInProductName() {
        String answer = "可考虑小米15 pro，价格5299元；徕卡光学与拍照偏好相关，实拍效果仍需核实。";
        var result = recommendationWith(List.of(Map.of(
                "code", "XIAOMI-15", "name", "小米 15 Pro", "price", 5299)),
                answer, "预算6000元以内并重视拍照");
        assertEquals(answer, result.answer());
        assertEquals("PASS", result.quality().status());
    }

    private AgentExecutionResponse recommendationWith(List<Map<String, Object>> products,
                                                       String answer, String question) {
        var service = mock(StreamingProductAgentService.class);
        when(service.verifyAnalysisAndRecommend(eq(question), anyString(), anyList(), anyString()))
                .thenReturn(DomainAgentResponse.of(answer, DomainQualityResult.pass(1, "VERIFIED")));
        var upstream = new AgentNodeOutput("analysis", "product", "SUCCEEDED", "目录分析",
                Map.of("products", products, "productCount", products.size()));
        return new ProductStreamController(service).execute(
                productStep("RECOMMEND_PRODUCT", question, upstream), null).getBody();
    }

    @Test
    void emptyTabletCatalogSurvivesDiscoveryAnalysisAndRecommendationWithoutModels() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        ProductDiscoveryService discovery = mock(ProductDiscoveryService.class);
        String question = "帮我推荐一款平板电脑，预算2000以内";
        when(discovery.discover(question, "平板电脑", null)).thenReturn(
                new ProductDiscoveryService.DiscoveryResult("暂无预算内平板电脑", 0, false,
                        List.of(), false, "平板电脑"));
        ProductStreamController controller = new ProductStreamController(service, discovery);
        var discoverResponse = controller.execute(new AgentExecutionRequest(
                "1.0", "empty-tablet", "discover", "42", "DISCOVER_PRODUCTS", question,
                Map.of("category", "平板电脑"), List.of(), List.of(), null, null), null);
        assertEquals("0", discoverResponse.getHeaders().getFirst(TokenUsageHeaders.TOTAL_TOKENS));
        var tools = com.example.smartassistant.common.audit.ToolUsageHeaders.decode(discoverResponse.getHeaders()
                .getFirst(com.example.smartassistant.common.audit.ToolUsageHeaders.TOOL_USAGE));
        org.junit.jupiter.api.Assertions.assertTrue(tools.complete());
        assertEquals(1, tools.calls().size());
        assertEquals("discoverProducts", tools.calls().getFirst().name());
        var discover = discoverResponse.getBody();
        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, discover.status());
        AgentNodeOutput previous = new AgentNodeOutput("discover", "product", "SUCCEEDED",
                discover.answer(), discover.data());
        for (String operation : List.of("ANALYZE_PRODUCT_DATA", "RECOMMEND_PRODUCT")) {
            var http = controller.execute(productStep(operation, question, previous), null);
            assertEquals("0", http.getHeaders().getFirst(TokenUsageHeaders.TOTAL_TOKENS));
            var noTools = com.example.smartassistant.common.audit.ToolUsageHeaders.decode(http.getHeaders()
                    .getFirst(com.example.smartassistant.common.audit.ToolUsageHeaders.TOOL_USAGE));
            org.junit.jupiter.api.Assertions.assertTrue(noTools.complete());
            assertEquals(0, noTools.calls().size());
            var result = http.getBody();
            assertEquals(AgentExecutionResponse.Status.SUCCEEDED, result.status());
            assertEquals("PASS", result.quality().status());
            assertEquals(List.of("EMPTY_PRODUCT_CATALOG"), result.quality().reasonCodes());
            assertEquals(List.of(), result.data().get("products"));
            assertEquals(0, result.data().get("productCount"));
            org.assertj.core.api.Assertions.assertThat(result.answer())
                    .contains("没有找到符合", "最看重哪项条件")
                    .doesNotContain("FATAL_FAILED", "分析", "审核", "iPad");
            previous = new AgentNodeOutput(operation, "product", "SUCCEEDED",
                    result.answer(), result.data());
        }
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void failedOrMissingCatalogMustNotBecomeSuccessfulNoMatch() {
        for (AgentNodeOutput upstream : List.of(
                new AgentNodeOutput("discover", "product", "FAILED", "超时",
                        Map.of("products", List.of(), "productCount", 0)),
                new AgentNodeOutput("analysis", "product", "SUCCEEDED", "数据缺失", Map.of()))) {
            StreamingProductAgentService service = mock(StreamingProductAgentService.class);
            when(service.verifyAnalysisAndRecommend(anyString(), anyString(), anyList(), anyString()))
                    .thenReturn(DomainAgentResponse.of("缺少可靠上下文",
                            DomainQualityResult.fail("MISSING_RECOMMENDATION_CONTEXT")));
            var result = new ProductStreamController(service).execute(
                    productStep("RECOMMEND_PRODUCT", "推荐平板电脑", upstream), null).getBody();
            org.assertj.core.api.Assertions.assertThat(result.status())
                    .isNotEqualTo(AgentExecutionResponse.Status.SUCCEEDED);
            verify(service).verifyAnalysisAndRecommend(anyString(), anyString(), anyList(), anyString());
        }
    }

    @Test
    void validBudgetTabletStillUsesVerifiedModelRecommendation() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        String answer = "推荐入门平板，价格1999元，符合2000元以内预算。";
        when(service.verifyAnalysisAndRecommend(anyString(), anyString(), anyList(), anyString()))
                .thenReturn(DomainAgentResponse.of(answer,
                        DomainQualityResult.pass(1.0, "PRODUCT_RECOMMENDATION_PRO_VERIFIED")));
        AgentNodeOutput upstream = new AgentNodeOutput("analysis", "product", "SUCCEEDED", "符合预算",
                Map.of("products", List.of(Map.of("code", "TABLET-1999", "name", "入门平板",
                        "price", 1999)), "productCount", 1));
        var result = new ProductStreamController(service).execute(productStep(
                "RECOMMEND_PRODUCT", "帮我推荐一款平板电脑，预算2000以内", upstream), null).getBody();
        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, result.status());
        assertEquals(answer, result.answer());
        verify(service).verifyAnalysisAndRecommend(anyString(), anyString(), anyList(), anyString());
    }

    @Test
    void rejectedOverBudgetRecommendationCannotBeOverriddenByCatalogFallback() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        when(service.verifyAnalysisAndRecommend(anyString(), anyString(), anyList(), anyString()))
                .thenReturn(DomainAgentResponse.of("候选超出预算",
                        DomainQualityResult.fail("PRODUCT_ANALYSIS_AUDIT_REJECTED")));
        AgentNodeOutput upstream = new AgentNodeOutput("analysis", "product", "SUCCEEDED", "待核实",
                Map.of("products", List.of(Map.of("code", "TABLET-9499", "name", "高价平板",
                        "price", 9499)), "productCount", 1));
        var result = new ProductStreamController(service).execute(productStep(
                "RECOMMEND_PRODUCT", "预算2000以内", upstream), null).getBody();
        org.assertj.core.api.Assertions.assertThat(result.status())
                .isNotEqualTo(AgentExecutionResponse.Status.SUCCEEDED);
        assertNull(result.answer());
        assertEquals("PRODUCT_ANALYSIS_AUDIT_REJECTED", result.error().code());
    }

    private static AgentExecutionRequest productStep(String operation, String question, AgentNodeOutput previous) {
        return new AgentExecutionRequest("1.0", "tablet-regression", operation, "42", operation,
                question, Map.of(), List.of(previous.nodeId()), List.of(), null, null,
                Map.of(previous.nodeId(), previous), "shopping", 1, "checksum", 0, "tablet-regression");
    }

    @Test
    void modelAnalysisAndAuditRejectionBothReturnMeasuredTokens() {
        for (boolean reject : List.of(false, true)) {
            var service = mock(StreamingProductAgentService.class);
            when(service.analyzeVerifiedContext(anyString(), anyString(), anyList(), anyString())).thenAnswer(invocation -> {
                TokenUsageCache.record(invocation.getArgument(3), 81, 19, 100);
                return DomainAgentResponse.of("核实结果", reject
                        ? DomainQualityResult.fail("AUDIT_REJECTED") : DomainQualityResult.pass(1, "VERIFIED"));
            });
            var upstream = new AgentNodeOutput("discover", "product", "SUCCEEDED", "商品", Map.of());
            var http = new ProductStreamController(service).execute(
                    productStep("ANALYZE_PRODUCT_DATA", "分析商品", upstream), null);
            assertEquals("100", http.getHeaders().getFirst(TokenUsageHeaders.TOTAL_TOKENS));
            assertNull(TokenUsageCache.snapshot("tablet-regression"));
            assertEquals(reject ? AgentExecutionResponse.Status.FAILED : AgentExecutionResponse.Status.SUCCEEDED,
                    http.getBody().status());
        }
    }

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
                "req-protocol", "1", "推荐办公电脑"), null);

        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, response.getBody().status());
        assertEquals("推荐 A 型号", response.getBody().answer());
        assertEquals("PASS", response.getBody().quality().status());
    }

    @Test
    void unifiedDiscoveryEndpointReturnsProductsAsStructuredData() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        ProductDiscoveryService discovery = mock(ProductDiscoveryService.class);
        when(discovery.supports("查询热门商品")).thenReturn(true);
        when(discovery.discover(eq("查询热门商品"), anyString(), eq(3))).thenReturn(
                new ProductDiscoveryService.DiscoveryResult(
                        "1. 降噪耳机（SKU-100） — ¥599", 1, true,
                        List.of(new ProductBackend.ProductSummary(
                                "SKU-100", "降噪耳机", new BigDecimal("599"),
                                "有货", "黑色", 12))));
        ProductStreamController controller = new ProductStreamController(service, discovery);
        AgentExecutionRequest request = new AgentExecutionRequest(
                "1.0", "scene-1", "hot-products", "42", "QUERY_HOT_PRODUCTS",
                "查询热门商品", Map.of("limit", 3), List.of(), List.of(), null,
                null);

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
        when(discovery.discover(eq(question), anyString(), isNull())).thenReturn(
                new ProductDiscoveryService.DiscoveryResult(
                        "目录证据不足，不能把热门等同于适合。", 1, true,
                        List.of(new ProductBackend.ProductSummary(
                                "SKU-100", "候选设备", new BigDecimal("599"),
                                "有货", null, 12)), true));
        ProductStreamController controller = new ProductStreamController(service, discovery);

        var response = controller.execute(AgentExecutionRequest.answer(
                "scene-limited", "42", question), null);

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
                null, predecessors, "shopping", 1, "sha256:v1", 0,
                "scene-analysis");
        when(service.analyzeVerifiedContext(
                org.mockito.ArgumentMatchers.eq("分析候选商品与用户预算的匹配度"),
                org.mockito.ArgumentMatchers.contains("SKU-100"),
                anyList(), org.mockito.ArgumentMatchers.eq("scene-analysis")))
                .thenReturn(DomainAgentResponse.of(
                        "### 数据分析开始 ###\n【核心结论】SKU-100 符合预算",
                        DomainQualityResult.pass(1.0, "VERIFIED_PRODUCT_ANALYSIS")));

        var response = controller.execute(request, null);

        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, response.getBody().status());
        assertEquals(List.of("discover_products"), response.getBody().data().get("sourceNodeIds"));
        assertEquals("### 数据分析开始 ###\n【核心结论】SKU-100 符合预算",
                response.getBody().data().get("analysis"));
        assertEquals(1, response.getBody().data().get("productCount"));
        org.assertj.core.api.Assertions.assertThat(
                (List<?>) response.getBody().data().get("products"))
                .singleElement().asString().contains("SKU-100");
        verify(service).analyzeVerifiedContext(
                org.mockito.ArgumentMatchers.eq("分析候选商品与用户预算的匹配度"),
                org.mockito.ArgumentMatchers.argThat(context ->
                        context.contains("SKU-100") && context.contains("结构化数据")
                                && context.contains("近30天") && context.contains("5分制")
                                && !context.contains("候选商品：")),
                anyList(), org.mockito.ArgumentMatchers.eq("scene-analysis"));
    }

    @Test
    void analysisNodeConsumesUserProfileInjectedByRouter() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        ProductStreamController controller = new ProductStreamController(service);
        AgentExecutionRequest request = new AgentExecutionRequest(
                "1.0", "scene-profile", "analyze_product_data", "42",
                "ANALYZE_PRODUCT_DATA", "分析候选商品",
                Map.of(RoutingKeys.USER_PROFILE_INPUT,
                        "【用户历史信息】\n- 预算范围: 5000元\n- 用途: 摄影"),
                List.of(), List.of(), null, null, Map.of(),
                "shopping", 1, "sha256:v1", 0, "scene-profile");
        when(service.analyzeVerifiedContext(eq("分析候选商品"),
                org.mockito.ArgumentMatchers.contains("预算范围: 5000元"),
                anyList(), eq("scene-profile")))
                .thenReturn(DomainAgentResponse.of("按预算完成分析",
                        DomainQualityResult.pass(1.0, "VERIFIED_PRODUCT_ANALYSIS")));

        var response = controller.execute(request, null);

        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, response.getBody().status());
        verify(service).analyzeVerifiedContext(eq("分析候选商品"),
                org.mockito.ArgumentMatchers.argThat(context ->
                        context.contains("[用户画像]")
                                && context.contains("预算范围: 5000元")
                                && context.contains("用途: 摄影")),
                anyList(), eq("scene-profile"));
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
                null, predecessors, "shopping", 1, "sha256:v1", 0,
                "scene-recommend");
        when(service.verifyAnalysisAndRecommend(
                org.mockito.ArgumentMatchers.eq("核实分析并推荐"),
                org.mockito.ArgumentMatchers.contains("SKU-100"),
                anyList(), org.mockito.ArgumentMatchers.eq("scene-recommend")))
                .thenReturn(DomainAgentResponse.of(
                        "推荐 SKU-100，价格 ¥599，库存有货",
                        DomainQualityResult.pass(1.0, "PRODUCT_RECOMMENDATION_PRO_VERIFIED")));

        var response = controller.execute(request, null);

        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, response.getBody().status());
        assertEquals("推荐 SKU-100，价格 ¥599，库存有货",
                response.getBody().data().get("recommendation"));
    }

    @Test
    void recommendationFallsBackToVerifiedCandidatesWhenModelOnlyRefuses() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        ProductStreamController controller = new ProductStreamController(service);
        Map<String, AgentNodeOutput> predecessors = Map.of(
                "discover_products", new AgentNodeOutput(
                        "discover_products", "product", "SUCCEEDED", "发现候选", Map.of(
                        "products", List.of(
                                Map.of("code", "SKU-100", "name", "降噪耳机",
                                        "price", 599, "stock", "有货", "popularity", 3),
                                Map.of("code", "SKU-200", "name", "会议耳机",
                                        "price", 799, "stock", "有货", "popularity", 3)))),
                "analyze_product_data", new AgentNodeOutput(
                        "analyze_product_data", "product", "SUCCEEDED",
                        "现有数据无法选出唯一商品", Map.of(
                        "analysis", "两个候选近期订单数相同，缺少口碑数据")));
        AgentExecutionRequest request = new AgentExecutionRequest(
                "1.0", "scene-fallback", "recommend_product", "42",
                "RECOMMEND_PRODUCT", "推荐现在的热门商品", Map.of(),
                List.of("discover_products", "analyze_product_data"), List.of(), null,
                null, predecessors, "shopping", 1, "sha256:v1", 0,
                "scene-fallback");
        when(service.verifyAnalysisAndRecommend(
                org.mockito.ArgumentMatchers.eq("推荐现在的热门商品"),
                org.mockito.ArgumentMatchers.contains("SKU-100"),
                anyList(), org.mockito.ArgumentMatchers.eq("scene-fallback")))
                .thenReturn(DomainAgentResponse.of(
                        "数据不足，暂时无法推荐。",
                        DomainQualityResult.pass(1.0, "PRODUCT_RECOMMENDATION_PRO_VERIFIED")));

        var response = controller.execute(request, null);

        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, response.getBody().status());
        assertEquals("WARN", response.getBody().quality().status());
        String answer = response.getBody().answer();
        org.assertj.core.api.Assertions.assertThat(answer)
                .contains("SKU-100", "SKU-200", "近30天站内销量均为 3")
                .doesNotContain("数据不足，暂时无法推荐");
        assertEquals(answer, response.getBody().data().get("recommendation"));
    }

    @Test
    void passesStructuredCategoryAndUsesCandidatePoolForTabletDiscovery() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        ProductDiscoveryService discovery = mock(ProductDiscoveryService.class);
        String rawQuestion = "我想买一部平板电脑，帮我推荐一款热门的&#x20;";
        String normalized = "我想买一部平板电脑，帮我推荐一款热门的";
        when(discovery.discover(normalized, "平板电脑", 1)).thenReturn(
                new ProductDiscoveryService.DiscoveryResult(
                        "近期热门平板电脑：iPad Pro M4", 1, true,
                        List.of(new ProductBackend.ProductSummary(
                                "IPAD-PRO-M4", "iPad Pro M4", new BigDecimal("9499"),
                                "充足", "M4 芯片", 3, "平板电脑")), false, "平板电脑"));
        ProductStreamController controller = new ProductStreamController(service, discovery);
        AgentExecutionRequest request = new AgentExecutionRequest(
                "1.0", "scene-tablet", "discover_products", "42",
                "DISCOVER_PRODUCTS", rawQuestion,
                Map.of("product_category", "平板电脑", "limit", 1),
                List.of(), List.of(), null, null);

        var response = controller.execute(request, null);

        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, response.getBody().status());
        assertEquals("平板电脑", response.getBody().data().get("category"));
        verify(discovery).discover(normalized, "平板电脑", 1);
    }
}
