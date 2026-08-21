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
}
