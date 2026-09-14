package com.example.smartassistant.service.core;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.agent.protocol.*;
import com.example.smartassistant.common.audit.TokenUsageHeaders;
import com.example.smartassistant.controller.ProductStreamController;
import com.example.smartassistant.service.agent.StreamingProductAgentService;
import com.example.smartassistant.service.quality.ProductDomainQualityValidator;
import com.example.smartassistant.service.search.ProductRagService;
import com.example.smartassistant.spi.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductRecommendationSafetyTest {
    @Test
    void vagueFeaturesClarifyRatherThanGuessACategoryOrRecommendPopularUnrelatedProducts() {
        ProductBackend backend = mock(ProductBackend.class);
        when(backend.listProductCategories()).thenReturn(List.of("手机", "笔记本电脑", "耳机"));
        var service = new ProductDiscoveryService(backend);
        String question = "推荐一款轻便、续航长的，预算2000以内";
        assertThat(service.supports(question)).isTrue();
        assertThat(service.supports("轻便、续航长")).isTrue();
        assertThat(service.supports("我需要便携降噪的")).isTrue();
        assertThat(service.supports("预算2000元")).isTrue();
        var result = service.discover(question, 5);
        assertThat(result.clarificationRequired()).isTrue();
        assertThat(result.answer()).contains("哪类商品", "已提供的预算").doesNotContain("暂无符合");
        verify(backend, never()).listPopularProducts(any(ProductBackend.ProductDiscoveryCriteria.class));
    }

    @Test
    void trendingWithoutCategoryShowsCrossCategoryListAndExplainsItsLimitedEvidence() {
        ProductBackend backend = mock(ProductBackend.class);
        when(backend.listProductCategories()).thenReturn(List.of("耳机", "平板电脑"));
        when(backend.listPopularProducts(any(ProductBackend.ProductDiscoveryCriteria.class))).thenReturn(List.of(
                new ProductBackend.ProductSummary("A", "耳机A", new BigDecimal("1999"), "充足", "降噪", 20, "耳机"),
                new ProductBackend.ProductSummary("B", "平板B", new BigDecimal("2999"), "充足", "11英寸", 10, "平板电脑")));
        var service = new ProductDiscoveryService(backend);
        assertThat(service.supports("最近流行什么商品")).isTrue();
        var result = service.discover("最近流行什么商品", "商品", 5);
        assertThat(result.browsingOnly()).isTrue();
        assertThat(result.clarificationRequired()).isFalse();
        assertThat(result.answer()).contains("耳机A", "平板B", "入选依据").doesNotContain("全网第一");
        assertThat(result.answer()).contains("不代表全网热度", "跨品类浏览");
    }

    @Test
    void nullCatalogResultIsFailureNotNoMatchingProducts() {
        ProductBackend backend = mock(ProductBackend.class);
        when(backend.listProductCategories()).thenReturn(List.of("手机"));
        when(backend.listPopularProducts(any(ProductBackend.ProductDiscoveryCriteria.class))).thenReturn(null);
        assertThatThrownBy(() -> new ProductDiscoveryService(backend).discover("热门商品", 5))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }

    @Test
    void databaseFailureReturnsRetryableProtocolErrorAndNeverCallsModel() {
        var agent = mock(StreamingProductAgentService.class);
        var controller = new ProductStreamController(agent,
                new ProductDiscoveryService(new JdbcProductBackend(null)));
        var http = controller.execute(request("DISCOVER_PRODUCTS", "热门商品", Map.of()), null);
        assertThat(http.getBody().status()).isEqualTo(AgentExecutionResponse.Status.RETRYABLE_FAILED);
        assertThat(http.getBody().error().code()).isEqualTo("PRODUCT_CATALOG_UNAVAILABLE");
        assertThat(http.getBody().error().message()).contains("暂时不可用").doesNotContain("暂无符合");
        assertThat(http.getHeaders().getFirst(TokenUsageHeaders.TOTAL_TOKENS)).isEqualTo("0");
        verifyNoInteractions(agent);
    }

    @Test
    void directEntryCatalogFailureCannotFallThroughToRagOrModel() {
        var agent = mock(SmartReActAgent.class);
        var rag = mock(ProductRagService.class);
        var service = new StreamingProductAgentService(agent, rag, new ProductDomainQualityValidator(),
                new ProductDiscoveryService(new JdbcProductBackend(null)));
        var result = service.executeWithQuality("热门商品", "catalog-failure");
        assertThat(result.quality().isFail()).isTrue();
        assertThat(result.quality().getReasonCodes()).contains("PRODUCT_CATALOG_UNAVAILABLE");
        assertThat(result.answer()).contains("暂时不可用");
        verifyNoInteractions(agent, rag);
    }

    @Test
    void clarificationAndBrowsingSurviveBothAnalysisAndRecommendationEdges() {
        for (String question : List.of("推荐一款轻便续航长的", "最近流行什么商品")) {
            var agent = mock(StreamingProductAgentService.class);
            var controller = new ProductStreamController(agent, new ProductDiscoveryService(new InMemoryProductBackend()));
            var discovery = controller.execute(request("DISCOVER_PRODUCTS", question, Map.of()), null).getBody();
            assertThat(discovery.quality().status()).isEqualTo("PASS");
            var analysis = controller.execute(request("ANALYZE_PRODUCT_DATA", question,
                    Map.of("discover", output("discover", discovery))), null).getBody();
            var recommendation = controller.execute(request("RECOMMEND_PRODUCT", question,
                    Map.of("analysis", output("analysis", analysis))), null).getBody();
            assertThat(recommendation.status()).isEqualTo(AgentExecutionResponse.Status.SUCCEEDED);
            assertThat(recommendation.answer()).isEqualTo(discovery.answer());
            assertThat(recommendation.quality().reasonCodes()).doesNotContain("EMPTY_PRODUCT_CATALOG");
            verifyNoInteractions(agent);
        }
    }

    @Test
    void recommendationReasonsConnectActualSpecsToNeedsWithoutInventingBenchmarkOrSavings() {
        var facts = new StructuredProductRecommendation("推荐拍照手机，预算6000元", List.of(Map.of(
                "code", "PHONE", "name", "手机A", "price", 5299, "stock", "充足", "spec", "徕卡光学", "rating", 4.8)));
        var decision = facts.parse("""
                {"valid":true,"selected_code":"PHONE","evidence_fields":["spec","rating"],"limitations":[]}
                """);
        assertThat(facts.renderRecommendation(decision))
                .contains("推荐理由", "预算上限", "你提到的拍照", "徕卡光学", "不能证明", "专项性能")
                .doesNotContain("701", "最强", "拍照评分4.8");
    }

    @Test
    void missingFeatureEvidenceIsNotReplacedWithAClaimAboutLongBatteryLife() {
        var facts = new StructuredProductRecommendation("轻便续航长的手机", List.of(Map.of(
                "code", "A", "name", "手机A", "price", 1000, "spec", "蓝色")));
        assertThat(facts.hasEligibleProducts()).isFalse();
        assertThat(facts.noEligibleAnswer()).contains("重量上限", "最低续航")
                .doesNotContain("你提到的续航", "满足长续航", "库存充足");
    }

    private static AgentNodeOutput output(String id, AgentExecutionResponse response) {
        return new AgentNodeOutput(id, "product", response.status().name(), response.answer(), response.data());
    }

    private static AgentExecutionRequest request(String operation, String question, Map<String, AgentNodeOutput> predecessors) {
        return new AgentExecutionRequest("1.0", "recommendation-safety", operation, "42", operation,
                question, Map.of(), List.copyOf(predecessors.keySet()), List.of(), null, null,
                predecessors, "shopping", 1, "checksum", 0, "recommendation-safety");
    }
}
