package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.ProductBackend;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProductFollowUpConstraintsTest {
    private static String context(String current, String... history) {
        return current + "\n\n[对话上下文]\n最近用户问题（按时间顺序，仅供解析指代）：\n用户："
                + String.join("\n用户：", history) + "\n请延续上一轮讨论的对象回答当前问题。";
    }

    @Test
    void acceptsWeightUpperBoundAnswersIncludingCommonTypo() {
        for (String text : List.of("重量上限1公斤", "重量上限为1公斤", "重量上线3公斤把，金额改为5000元",
                "重量上限设为1.3kg", "重量上限：1300克")) {
            var result = ProductFeatureRequest.parse(text);
            assertThat(result.clarification()).as(text).isEmpty();
            assertThat(result.constraints().maxWeightGrams()).isPositive();
        }
    }

    @Test
    void latestExplicitConstraintsReplaceHistoricalValuesOnConfirmation() {
        String query = context("确认按这个条件来筛选", "帮我选一款便携式笔记本，预算3000以内",
                "重量上限为1公斤，商品名称为联想", "重量上限1公斤，金额为3000元",
                "重量上线3公斤把，金额改为5000元");
        // Controller whitespace normalization must not destroy chronology.
        query = com.example.smartassistant.common.util.UserQuestionNormalizer.normalize(query);
        var features = ProductFeatureRequest.parse(query);
        assertThat(features.clarification()).isEmpty();
        assertThat(features.constraints().maxWeightGrams()).isEqualByComparingTo("3000");
        assertThat(ProductDiscoveryService.resolveBudget(query).max()).isEqualByComparingTo("5000");
        ProductBackend backend = mock(ProductBackend.class);
        when(backend.listProductCategories()).thenReturn(List.of("笔记本电脑", "耳机"));
        when(backend.listPopularProducts(any(ProductBackend.ProductDiscoveryCriteria.class))).thenReturn(List.of());
        var result = new ProductDiscoveryService(backend).discover(query, "联想", 5);
        assertThat(result.clarificationRequired()).isFalse();
        assertThat(result.category()).isEqualTo("笔记本电脑");
        verify(backend).listPopularProducts(argThat((ProductBackend.ProductDiscoveryCriteria c) ->
                c.category().equals("笔记本电脑") && c.maxPrice().intValueExact() == 5000
                        && c.features().maxWeightGrams().intValueExact() == 3000));
    }

    @Test
    void unchangedBudgetAndWeightSurvivePartialUpdates() {
        String query = context("金额改为5000元", "推荐便携笔记本预算3000以内", "重量上限1公斤");
        assertThat(ProductFeatureRequest.parse(query).constraints().maxWeightGrams()).isEqualByComparingTo("1000");
        assertThat(ProductDiscoveryService.resolveBudget(query).max()).isEqualByComparingTo("5000");
    }

    @Test
    void sameTurnConflictsAndAmbiguityStillRequireClarification() {
        assertThat(ProductFeatureRequest.parse("重量不超过1公斤，也不超过3公斤").clarification()).isNotEmpty();
        assertThat(ProductFeatureRequest.parse("重量上限0公斤").clarification()).isNotEmpty();
        assertThat(ProductDiscoveryService.resolveBudget(context("预算3000或5000元", "预算2000元")).ambiguous()).isTrue();
        assertThat(ProductDiscoveryService.resolveBudget("重量上限3公斤").max()).isNull();
    }
}
