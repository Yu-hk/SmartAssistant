package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.model.ExecutionPlan;
import com.example.smartassistant.common.quality.DomainQualityResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultMergerTest {

    @Test
    void recognizesSuccessfulSingleDomainOrderWorkflow() {
        List<SubTaskResult> results = List.of(
                verifiedOrderResult("query", "查询订单", "状态：待付款"),
                verifiedOrderResult("pending", "查询确认项", "存在支付确认项"));

        assertThat(ResultMerger.isDeterministicOrderWorkflow(results)).isTrue();
    }

    @Test
    void keepsModelMergeForUnstructuredOrderResults() {
        List<SubTaskResult> results = List.of(
                new SubTaskResult("query", "查询订单", "order", "状态：待付款", true),
                new SubTaskResult("pending", "查询确认项", "order", "存在支付确认项", true));

        assertThat(ResultMerger.isDeterministicOrderWorkflow(results)).isFalse();
    }

    @Test
    void keepsModelMergeForMixedDomains() {
        List<SubTaskResult> results = List.of(
                new SubTaskResult("product", "查询商品", "product", "库存充足", true),
                new SubTaskResult("order", "查询订单", "order", "状态：待付款", true));

        assertThat(ResultMerger.isDeterministicOrderWorkflow(results)).isFalse();
    }

    @Test
    void replacePolicySupersedesEarlierParallelConclusions() {
        SubTaskResult analysis = successful("analysis", "分析结果", "评分第一：SKU-OLD");
        analysis.setMergePolicy(ExecutionPlan.MergePolicy.STRUCTURED);
        SubTaskResult verifiedRecommendation = successful(
                "recommend", "核实推荐", "最终推荐：SKU-NEW");
        verifiedRecommendation.setMergePolicy(ExecutionPlan.MergePolicy.REPLACE);

        assertThat(ResultMerger.applyMergePolicies(List.of(analysis, verifiedRecommendation)))
                .extracting(SubTaskResult::getTaskId)
                .containsExactly("recommend");
    }

    @Test
    void distinguishesRequiredAndOptionalFailures() {
        SubTaskResult required = new SubTaskResult(
                "inventory", "查询库存", "product", "超时", false,
                SubTaskResult.ErrorType.RETRYABLE_FAILED);
        SubTaskResult optional = new SubTaskResult(
                "reviews", "查询补充口碑", "product", "不可用", false,
                SubTaskResult.ErrorType.FATAL_FAILED);
        optional.setRequired(false);

        assertThat(ResultMerger.requiredFailures(List.of(required, optional)))
                .extracting(SubTaskResult::getTaskId)
                .containsExactly("inventory");
    }

    @Test
    void acceptsOnlyVerifiedStructuredResultsForDeterministicMerge() {
        SubTaskResult first = successful("sales", "销量分析", "销量事实");
        first.setMergePolicy(ExecutionPlan.MergePolicy.STRUCTURED);
        first.setStructuredData(Map.of("sales", 100));
        first.setDomainQuality(DomainQualityResult.pass(1.0, "VERIFIED"));
        SubTaskResult second = successful("reviews", "口碑分析", "口碑事实");
        second.setMergePolicy(ExecutionPlan.MergePolicy.STRUCTURED);
        second.setStructuredData(Map.of("score", 4.8));
        second.setDomainQuality(DomainQualityResult.pass(1.0, "VERIFIED"));

        assertThat(ResultMerger.isStructuredWorkflow(List.of(first, second))).isTrue();
        second.setDomainQuality(DomainQualityResult.unknown());
        assertThat(ResultMerger.isStructuredWorkflow(List.of(first, second))).isFalse();
    }

    @Test
    void detectsConflictingFactsForSameSchemaAndEntity() {
        SubTaskResult inventoryA = successful("inventory_a", "库存查询 A", "库存 10");
        inventoryA.setOutputSchema("inventory.v1");
        inventoryA.setStructuredData(Map.of("sku", "SKU-100", "stock", 10));
        SubTaskResult inventoryB = successful("inventory_b", "库存查询 B", "库存 8");
        inventoryB.setOutputSchema("inventory.v1");
        inventoryB.setStructuredData(Map.of("sku", "SKU-100", "stock", 8));

        assertThat(ResultMerger.structuredConflicts(List.of(inventoryA, inventoryB)))
                .singleElement()
                .asString().contains("inventory.v1.stock", "inventory_a", "inventory_b");
    }

    private static SubTaskResult verifiedOrderResult(
            String taskId, String description, String result) {
        SubTaskResult value = new SubTaskResult(
                taskId, description, "order", result, true);
        value.setDomainQuality(DomainQualityResult.pass(1.0, "DETERMINISTIC_ORDER_QUERY"));
        value.setStructuredData(Map.of("verified", true, "criteriaSatisfied", true));
        return value;
    }

    private static SubTaskResult successful(String taskId, String description, String result) {
        return new SubTaskResult(taskId, description, "product", result, true);
    }
}
