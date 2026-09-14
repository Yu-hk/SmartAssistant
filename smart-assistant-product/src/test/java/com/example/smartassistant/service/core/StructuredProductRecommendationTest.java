package com.example.smartassistant.service.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class StructuredProductRecommendationTest {
    private static final String DECISION = """
            {"valid":true,"selected_code":"PHONE","evidence_fields":["spec","rating","reviewCount"],
             "limitations":["SINGLE_CANDIDATE","NO_PHOTO_BENCHMARK"],"issues":[],"correction_instruction":""}
            """;
    private StructuredProductRecommendation facts(String question, Object price) {
        return new StructuredProductRecommendation(question, List.of(Map.of("code", "PHONE", "name", "小米 15 Pro",
                "price", price, "spec", "徕卡光学", "rating", 4.8, "reviewCount", 8620, "stock", "充足")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"预算6000元以内推荐拍照手机", "预算：6,000元", "6000元以内的手机", "预算0.6万元", "预算6k"})
    void defaultOnlyShowsBudgetStatus(String question) {
        var facts = facts(question, 5299);
        var decision = facts.parse(DECISION);
        assertThat((BigDecimal) facts.budgetData().get("maxBudget")).isEqualByComparingTo("6000");
        assertThat(facts.renderRecommendation(decision)).contains("5299元，未超预算", "徕卡光学", "4.8/5", "8620")
                .doesNotContain("701", "剩余", "差额", "预算计算");
        assertThat(facts.renderAnalysis(decision)).contains("未超预算").doesNotContain("701", "剩余", "差额");
        assertThat(facts.promptData()).doesNotContain("remainder", "701");
    }

    @ParameterizedTest
    @ValueSource(strings = {"预算6000元，请计算剩余预算", "预算6000元，请给出差额和算式"})
    void explicitCalculationIsSeparateFromConclusion(String question) {
        var facts = facts(question, 5299);
        String[] sections = facts.renderRecommendation(facts.parse(DECISION)).split("按你的要求补充预算计算：");
        assertThat(sections).hasSize(2);
        assertThat(sections[0]).contains("未超预算").doesNotContain("701", "剩余", "差额");
        assertThat(sections[1]).contains("6000 − 5299 = 701元");
        assertThat(facts.renderAnalysis(facts.parse(DECISION))).doesNotContain("701", "差额");
    }

    @ParameterizedTest
    @ValueSource(strings = {"预算6000元，不要计算差额", "预算6000元，无需显示剩余", "预算6000元，只关心是否超预算，不需要差额",
            "预算6000元，不计算差额", "预算6000元，不算差额", "预算6000元，不显示剩余预算",
            "预算6000元，不提供算式", "预算6000元，不说明结余", "预算6000元，只说明是否超预算，不计算差额",
            "预算6000元，只告诉我是否超预算，差额不用管"})
    void negatedDetailsAreNotRendered(String question) {
        var facts = facts(question, 5299);
        assertThat(facts.renderRecommendation(facts.parse(DECISION))).doesNotContain("701", "预算计算");
    }

    @Test
    void exactBudgetAndDecimalAmountsUseBigDecimal() {
        var exact = facts("预算5299元", "5299.00");
        assertThat(exact.renderRecommendation(exact.parse(DECISION))).contains("未超预算");
        var decimal = facts("预算6000.10元，计算剩余预算", "5299.09");
        assertThat(decimal.renderRecommendation(decimal.parse(DECISION))).contains("6000.1 − 5299.09 = 701.01元");
    }

    @ParameterizedTest
    @ValueSource(strings = {"6000.01", "9499", "unknown", "-1"})
    void OverBudgetOrUnknownPricesAreNeverEligible(String price) {
        var facts = facts("预算6000元", price);
        assertThat(facts.hasEligibleProducts()).isFalse();
        assertThatThrownBy(() -> facts.parse(DECISION)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unspecifiedBudgetDoesNotInventOne() {
        var facts = facts("推荐拍照手机", 5299);
        assertThat(facts.renderRecommendation(facts.parse(DECISION))).contains("未提供明确预算上限").doesNotContain("未超预算");
    }

    @Test
    void punctuationDoesNotMergeBudgetAndOtherAmounts() {
        assertThat(ProductDiscoveryService.extractMaxBudget("预算6000，5299元的手机怎么样"))
                .isEqualByComparingTo("6000");
        assertThat(ProductDiscoveryService.extractMaxBudget("预算6000,5299元的手机怎么样"))
                .isEqualByComparingTo("6000");
        assertThat(facts("预算0元", 5299).hasEligibleProducts()).isFalse();
    }

    @Test
    void unsupportedFieldsIdentityAndTrailingContentFailClosed() {
        var facts = facts("预算6000元", 5299);
        for (String raw : List.of(DECISION.replace("PHONE", "OTHER"), DECISION + "{}",
                DECISION.replace("\"issues\":[]", "\"conclusion\":\"剩余700元\""),
                DECISION.replace("\"issues\":[]", "\"issues\":[\"价格错误\"]"),
                DECISION.replace("\"valid\":true", "\"valid\":false,\"valid\":true"),
                DECISION.replace("reviewCount", "cameraScore"))) {
            assertThatThrownBy(() -> facts.parse(raw)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void missingEvidenceAndDuplicateIdentityFailClosed() {
        var product = Map.of("code", "PHONE", "name", "手机", "price", 5299);
        var facts = new StructuredProductRecommendation("预算6000元", List.of(product));
        assertThatThrownBy(() -> facts.parse(DECISION)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredProductRecommendation("预算6000元", List.of(product, product)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredProductRecommendation("预算6000元", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
