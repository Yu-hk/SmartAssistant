package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.SubTaskResult;
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

    private static SubTaskResult verifiedOrderResult(
            String taskId, String description, String result) {
        SubTaskResult value = new SubTaskResult(
                taskId, description, "order", result, true);
        value.setDomainQuality(DomainQualityResult.pass(1.0, "DETERMINISTIC_ORDER_QUERY"));
        value.setStructuredData(Map.of("verified", true, "criteriaSatisfied", true));
        return value;
    }
}
