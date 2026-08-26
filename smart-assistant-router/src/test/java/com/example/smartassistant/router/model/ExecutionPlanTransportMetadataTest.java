package com.example.smartassistant.router.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionPlanTransportMetadataTest {

    @Test
    void keepsExplicitAccessModeWhenConvertingToIntentGraph() {
        ExecutionPlan plan = new ExecutionPlan(
                "execution-1", "查询商品后创建订单", List.of("必须确认"), List.of(
                new ExecutionPlan.TaskNode(
                        "products", ExecutionPlan.Domain.PRODUCT, "QUERY_PRODUCT", "查询商品",
                        Map.of(), List.of(), ExecutionPlan.AccessMode.READ, List.of(), null,
                        false, "返回可售 SKU", ExecutionPlan.MergePolicy.STRUCTURED),
                new ExecutionPlan.TaskNode(
                        "order", ExecutionPlan.Domain.ORDER, "CREATE_ORDER", "创建订单",
                        Map.of(), List.of("products"), ExecutionPlan.AccessMode.WRITE, List.of(),
                        "idem-1", true, "返回待确认订单", ExecutionPlan.MergePolicy.STRUCTURED)));

        IntentGraph graph = plan.toIntentGraph();

        assertThat(graph.getRootNodes()).extracting(IntentGraph.IntentNode::getAccessMode)
                .containsExactly("READ");
        assertThat(graph.getExecutableNodes(java.util.Set.of("products")))
                .extracting(IntentGraph.IntentNode::getAccessMode)
                .containsExactly("WRITE");
    }

    @Test
    void transportsNodeCommunicationAndMergeContractsToIntentGraph() {
        ExecutionPlan plan = new ExecutionPlan(
                "execution-2", "根据分析结果推荐商品", List.of(), List.of(
                new ExecutionPlan.TaskNode(
                        "analysis", ExecutionPlan.Domain.PRODUCT, "ANALYZE_PRODUCT_DATA",
                        "分析候选商品", Map.of(), List.of(), ExecutionPlan.AccessMode.READ,
                        List.of(), null, false, "生成评分", ExecutionPlan.MergePolicy.STRUCTURED,
                        true, "product-analysis.v1", Map.of()),
                new ExecutionPlan.TaskNode(
                        "recommend", ExecutionPlan.Domain.PRODUCT, "RECOMMEND_PRODUCT",
                        "核实并推荐商品", Map.of(), List.of("analysis"), ExecutionPlan.AccessMode.READ,
                        List.of(), null, false, "返回真实 SKU", ExecutionPlan.MergePolicy.REPLACE,
                        false, "product-recommendation.v1",
                        Map.of("analysis", "$.nodes.analysis.data.analysis"))));

        IntentGraph.IntentNode recommendation = plan.toIntentGraph().getAllNodes().stream()
                .filter(node -> "recommend".equals(node.getId()))
                .findFirst().orElseThrow();

        assertThat(recommendation.isRequired()).isFalse();
        assertThat(recommendation.getMergePolicy()).isEqualTo(ExecutionPlan.MergePolicy.REPLACE);
        assertThat(recommendation.getOutputSchema()).isEqualTo("product-recommendation.v1");
        assertThat(recommendation.getInputBindings())
                .containsEntry("analysis", "$.nodes.analysis.data.analysis");
    }
}
