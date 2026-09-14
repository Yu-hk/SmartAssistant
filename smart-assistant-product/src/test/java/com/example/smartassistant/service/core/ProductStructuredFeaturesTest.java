package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.*;
import com.example.smartassistant.spi.ProductBackend.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProductStructuredFeaturesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static List<ProductSummary> fixtures() throws Exception {
        try (var input = ProductStructuredFeaturesTest.class.getResourceAsStream("/product-structured-features-fixture.json")) {
            return JSON.readValue(input, new TypeReference<>() { });
        }
    }
    private static ProductBackend backend() throws Exception {
        List<ProductSummary> all = fixtures();
        return new InMemoryProductBackend() {
            @Override public List<String> listProductCategories() { return all.stream().map(ProductSummary::category).distinct().toList(); }
            @Override public List<ProductSummary> listPopularProducts(ProductDiscoveryCriteria criteria) { return matching(criteria).stream().limit(criteria.limit()).toList(); }
            @Override public List<String> listMatchingCategories(ProductDiscoveryCriteria criteria) { return matching(criteria).stream().map(ProductSummary::category).distinct().toList(); }
            private List<ProductSummary> matching(ProductDiscoveryCriteria c) {
                return all.stream().filter(p -> c.category().isBlank() || c.category().equals(p.category()))
                        .filter(p -> c.maxPrice() == null || p.price().compareTo(c.maxPrice()) <= 0)
                        .filter(p -> c.features().matches(p.features()))
                        .sorted(Comparator.comparingLong(ProductSummary::popularity).reversed()).toList();
            }
        };
    }

    @ParameterizedTest
    @ValueSource(strings={"重量不超过1.3kg", "重量≤1.3公斤", "重量1300克以内", "最多1300g"})
    void normalizesGramsAndKilogramsWithoutCreatingAMonetaryBudget(String query) {
        var request = ProductFeatureRequest.parse(query);
        assertThat(request.clarification()).isEmpty();
        assertThat(request.constraints().maxWeightGrams()).isEqualByComparingTo("1300");
        assertThat(ProductDiscoveryService.extractMaxBudget(query)).isNull();
        assertThat(ProductDiscoveryService.extractMaxBudget(query + "，预算5000元")).isEqualByComparingTo("5000");
    }

    @ParameterizedTest
    @ValueSource(strings={"轻便续航长", "重量小于1.3kg", "续航至少10小时", "视频播放续航至少0小时", "重量不超过0克",
            "重量不超过1.3kg且不低于1kg", "重量不超过300克或者支持主动降噪", "需要通话降噪耳机"})
    void ambiguousOrUnsupportedRequirementsAreClarified(String query) {
        assertThat(ProductFeatureRequest.parse(query).clarification()).isNotBlank();
    }

    @Test
    void filtersHardFeaturesBeforePopularityAndReturnsActualReasons() throws Exception {
        var result = new ProductDiscoveryService(backend()).discover(
                "推荐笔记本电脑，预算5000元，重量不超过1.3kg，视频播放续航至少10小时", 1);
        assertThat(result.products()).extracting(ProductSummary::code).containsExactly("FEATURE-TEST-LAPTOP-A");
        assertThat(result.answer()).contains("1200克", "12小时", "视频播放", "数据来源", "核验时间", "满足本次明确的特征条件")
                .doesNotContain("重型本B", "场景不同本C");
    }

    @Test
    void noCategoryWithUniqueVerifiedFeatureMatchInfersOnlyThatCategory() throws Exception {
        var result = new ProductDiscoveryService(backend()).discover(
                "推荐支持主动降噪的，预算1000元，重量不超过300克，开启降噪听歌续航至少25小时", 5);
        assertThat(result.clarificationRequired()).isFalse();
        assertThat(result.category()).isEqualTo("耳机");
        assertThat(result.products()).extracting(ProductSummary::code).containsExactly("FEATURE-TEST-HEADSET-A");
    }

    @Test
    void crossCategoryNumericMatchesClarifyEvenWhenLimitIsOne() throws Exception {
        var result = new ProductDiscoveryService(backend()).discover(
                "重量不超过1.3kg，视频播放续航至少10小时", 1);
        assertThat(result.clarificationRequired()).isTrue();
        assertThat(result.answer()).contains("笔记本电脑", "平板电脑", "哪类商品");
        assertThat(result.products()).isEmpty();
    }

    @Test
    void documentedFalseDiffersFromUnknownAndMissingSource() throws Exception {
        var anc = ProductFeatureRequest.parse("需要主动降噪").constraints();
        var noAnc = ProductFeatureRequest.parse("不支持主动降噪").constraints();
        assertThat(fixtures().stream().filter(p -> anc.matches(p.features())).map(ProductSummary::code))
                .containsExactly("FEATURE-TEST-HEADSET-A");
        assertThat(fixtures().stream().filter(p -> noAnc.matches(p.features())).map(ProductSummary::code))
                .containsExactly("FEATURE-TEST-HEADSET-B");
        assertThat(ProductFeatureRequest.parse("不需要主动降噪").constraints().active()).isFalse();
    }

    @Test
    void mismatchedBatteryScenarioAndUnknownFactsCannotSatisfyThresholds() {
        var requirement = ProductFeatureRequest.parse("视频播放续航至少10小时").constraints();
        assertThat(requirement.matches(new ProductFeatures(null, new BigDecimal("30"), "mixed_use", null, "test", "2026-09-14"))).isFalse();
        assertThat(requirement.matches(ProductFeatures.UNKNOWN)).isFalse();
        assertThat(requirement.matches(new ProductFeatures(null, BigDecimal.TEN, "video_playback", null, "test", "2026-09-14"))).isTrue();
    }

    @Test
    void oldCatalogShapeDeserializesWithUnknownFeatures() throws Exception {
        ProductSummary old = JSON.readValue("{\"code\":\"OLD\",\"name\":\"旧商品\",\"price\":1000}", ProductSummary.class);
        assertThat(old.features()).isEqualTo(ProductFeatures.UNKNOWN);
        assertThat(old.features().noiseCancelling()).isNull();
    }

    @Test
    void productFeatureQuestionIsNotRewrittenAsAnUnrelatedGenericRecommendation() throws Exception {
        assertThat(new ProductDiscoveryService(backend()).supports("AirPods Pro支持主动降噪吗")).isFalse();
        assertThat(new ProductDiscoveryService(backend()).supports("这个笔记本电脑重量多少")).isFalse();
    }

    @Test
    void decisionCannotChooseNonMatchingProductEvenIfModelPrefersItsPopularity() throws Exception {
        List<Map<?, ?>> catalog = new ArrayList<>();
        for (var p : fixtures()) catalog.add(JSON.convertValue(p, Map.class));
        var facts = new StructuredProductRecommendation("预算5000元，重量不超过1300克，视频播放续航至少10小时", catalog);
        assertThatThrownBy(() -> facts.parse(decision("FEATURE-TEST-LAPTOP-B"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facts.parse(decision("FEATURE-TEST-LAPTOP-C"))).isInstanceOf(IllegalArgumentException.class);
        String answer = facts.renderRecommendation(facts.parse(decision("FEATURE-TEST-LAPTOP-A")));
        assertThat(answer).contains("未超预算", "1200克", "12小时", "结构化证据", "明确给出的特征条件")
                .doesNotContain("预算剩余", "差额");
        assertThat(facts.promptData()).contains("weightGrams", "batteryLifeScenario", "features");
    }

    @Test
    void knownCategoryWithNoEvidenceReportsLimitInsteadOfPretendingOutOfStock() throws Exception {
        var result = new ProductDiscoveryService(backend()).discover("笔记本电脑，重量不超过100克", 5);
        assertThat(result.productCount()).isZero();
        assertThat(result.answer()).contains("没有可核实满足", "字段缺失不代表不支持").doesNotContain("缺货");
    }

    private static String decision(String code) {
        return "{\"valid\":true,\"selected_code\":\"" + code + "\",\"evidence_fields\":[\"features\"],\"limitations\":[]}";
    }
}
