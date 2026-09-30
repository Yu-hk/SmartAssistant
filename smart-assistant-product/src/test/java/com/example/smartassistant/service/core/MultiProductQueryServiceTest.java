package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.*;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultiProductQueryServiceTest {
    private final ProductBackend backend = mock(ProductBackend.class);
    // Exercise the real HTTP capability's service entry, not just the new helper.
    private final ProductFactQueryService service = new ProductFactQueryService(backend);
    private AgentExecutionResponse ask(String query) { return service.query(query, List.of(), "multi-test"); }
    private void known(String requested, String code, String price, ProductFeatures features) {
        when(backend.lookupFacts(requested)).thenReturn(new ProductBackend.FactLookup(List.of(
                new ProductBackend.ProductFact(code, requested, new BigDecimal(price), "充足", "耳机", "白色", features,
                        new ProductSuitability(List.of("成年人"), List.of("通勤"), "目录编辑", "2026-09-30"))), false));
    }
    private void catalog() {
        known("耳机A", "A", "1999", new ProductFeatures(new BigDecimal("5.3"), new BigDecimal("6"),
                "audio_anc_on", true, "厂商资料", "2026-09-30"));
        known("耳机B", "B", "1499", new ProductFeatures(new BigDecimal("7"), new BigDecimal("10"),
                "audio_anc_off", false, "厂商资料", "2026-09-30"));
    }
    @Test void independentFactsKeepIdentityAndDoNotEmitOrderQuote() {
        catalog();
        var response = ask("耳机A和耳机B分别多少钱？有货吗？");
        assertThat(response.data().get("handled")).isEqualTo(true);
        assertThat(response.answer()).contains("耳机A：目录售价 1999 元；库存充足", "耳机B：目录售价 1499 元；库存充足");
        assertThat(response.data()).doesNotContainKey("orderQuote");
        assertThat(response.data().get("productCoverage")).isEqualTo(Map.of("requested", 2, "resolved", 2));
        verify(backend).lookupFacts("耳机A"); verify(backend).lookupFacts("耳机B");
        verifyNoMoreInteractions(backend);
    }
    @Test void combinedBudgetIsNotAppliedToIndividualItems() {
        catalog();
        var response = ask("耳机A和耳机B合计不超过3000元可以吗？");
        assertThat(response.answer()).contains("每款各 1 件", "3498 元", "超过总预算 3000 元");
        assertThat(((Map<?, ?>)response.data().get("queryPlan")).get("budgetScope")).isEqualTo("TOTAL");
    }
    @Test void perItemBudgetStaysPerItem() {
        catalog();
        assertThat(ask("耳机A和耳机B每款不超过1800元可以吗？").answer())
                .contains("耳机A：超过每款 1800 元", "耳机B：符合每款 1800 元").doesNotContain("合计");
    }
    @Test void comparisonPreservesBatteryScenariosAndPurposeMeaning() {
        catalog();
        assertThat(ask("耳机A和耳机B续航哪个更长？").answer())
                .contains("开启主动降噪听歌", "关闭主动降噪听歌", "不能直接");
        assertThat(ask("耳机A和耳机B分别多少钱？哪个更适合通勤？").answer())
                .contains("1999", "1499", "目录标注", "尚不能确认哪款更适合通勤");
    }
    @Test void missingOrAmbiguousEntityDoesNotBorrowOtherProductsFacts() {
        catalog();
        when(backend.lookupFacts("XM5")).thenReturn(new ProductBackend.FactLookup(List.of(), true));
        var response = ask("耳机A和XM5分别多少钱？");
        assertThat(response.answer()).contains("耳机A：目录售价 1999 元", "XM5：匹配到多个版本");
        assertThat(response.data().get("clarificationRequired")).isEqualTo(true);
        when(backend.lookupFacts("未知耳机")).thenReturn(new ProductBackend.FactLookup(List.of(), false));
        assertThat(ask("耳机A和未知耳机合计多少钱？").answer()).contains("未找到准确匹配", "暂不能核算总价");
    }
    @Test void aliasesResolvingToSameProductRequireQuantityClarification() {
        catalog(); known("耳机别名", "A", "1999", ProductFeatures.UNKNOWN);
        assertThat(ask("耳机A和耳机别名合计多少钱？").answer()).contains("同一商品", "暂不能核算总价").doesNotContain("3998");
    }
    @Test void unreviewedFeaturesStayUnknownInEvidence() {
        catalog(); known("耳机B", "B", "1499", ProductFeatures.UNKNOWN);
        var response = ask("耳机A和耳机B重量和降噪如何？");
        assertThat(response.answer()).contains("耳机A：设备净重 5.3 克；支持主动降噪", "耳机B：重量资料尚未核实；主动降噪资料尚未核实");
        var items = (List<?>) response.data().get("productEvidence");
        var fields = (Map<?, ?>)((Map<?, ?>)items.get(1)).get("fields");
        assertThat(((Map<?, ?>)fields.get("WEIGHT")).get("known")).isEqualTo(false);
    }
    @Test void compatibilityDoesNotInferSupportFromIndependentSpecs() {
        catalog();
        assertThat(ask("耳机A和耳机B能否一起使用？").answer()).contains("不足以核实", "不能据此承诺");
    }
    @Test void catalogFailureRemainsRetryable() {
        catalog(); when(backend.lookupFacts("耳机B")).thenThrow(new ProductCatalogUnavailableException());
        assertThat(ask("耳机A和耳机B分别多少钱？").status()).isEqualTo(AgentExecutionResponse.Status.RETRYABLE_FAILED);
    }
    @Test void sameFieldPriceComparisonIsCalculatedFromCatalog() {
        catalog();
        assertThat(ask("耳机A和耳机B哪个更便宜？").answer()).contains("最低为 1499 元", "相差 500 元");
    }
    @ParameterizedTest
    @ValueSource(strings = {"耳机A和耳机B分别多少钱？", "请对比一下耳机A与耳机B的重量和续航？", "耳机A、耳机B各自的价格是多少？", "耳机A和耳机B都要支持主动降噪，有货吗？"})
    void recognizedReadOnlyPatterns(String query) { assertThat(MultiProductQueryPlan.parse(query)).isPresent(); }
    @ParameterizedTest
    @ValueSource(strings = {"耳机A和耳机B多少钱然后下单", "耳机A和耳机B重量低于6克", "耳机A和耳机B各两件合计多少钱", "耳机A和耳机B合计低于3000元", "耳机A和耳机B每款不超过1000元总预算1500元", "耳机A和耳机B多少钱并且给我退款", "耳机A和耳机B哪个支持卫星通信", "耳机A重量和耳机B价格", "A和B和C和D和E分别多少钱", "耳机A和耳机A分别多少钱"})
    void unparsedConstraintsAndWritesAreNotSilentlyDropped(String query) { assertThat(MultiProductQueryPlan.parse(query)).isEmpty(); }
}
