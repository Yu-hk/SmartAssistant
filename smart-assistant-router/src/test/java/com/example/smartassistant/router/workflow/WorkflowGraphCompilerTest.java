package com.example.smartassistant.router.workflow;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.ExecutionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowGraphCompilerTest {

    @Test
    void mapsVersionedDslToIntentGraphConsumedByLangGraph() {
        WorkflowDefinition definition = new WorkflowDefinition(1, "order-flow", null, 3, List.of(
                new WorkflowDefinition.WorkflowNode(
                        "lookup", WorkflowDefinition.NodeType.AGENT, "lookup product", "product",
                        "QUERY", Map.of("keyword", "phone"), List.of(), List.of(),
                        "has products", false, List.of("tenant scoped"), null),
                new WorkflowDefinition.WorkflowNode(
                        "create", WorkflowDefinition.NodeType.HUMAN_APPROVAL, "create order", "order",
                        "CREATE_ORDER", Map.of(), List.of("lookup"), List.of(),
                        "order created", true, List.of(), "order-${requestId}")), Map.of());

        IntentGraph graph = new WorkflowGraphCompiler().compile(definition, "buy a phone");

        assertEquals("buy a phone", graph.getQuestion());
        assertEquals(3, graph.getMaxGraphIterations());
        assertEquals(List.of("lookup"), graph.getAllNodes().stream()
                .filter(node -> node.getId().equals("create")).findFirst().orElseThrow().getDependsOn());
        assertTrue(graph.getAllNodes().stream()
                .filter(node -> node.getId().equals("create")).findFirst().orElseThrow()
                .isHumanApprovalRequired());
    }

    @Test
    void compilesCommunicationAndMergeContracts() {
        WorkflowDefinition.WorkflowNode node = new WorkflowDefinition.WorkflowNode(
                "recommend", WorkflowDefinition.NodeType.AGENT, "recommend", "product",
                "RECOMMEND_PRODUCT", Map.of(), List.of("analysis"), List.of(),
                "has sku", false, List.of(), null, false,
                ExecutionPlan.MergePolicy.REPLACE, "recommendation.v1",
                Map.of("analysis", "$.nodes.analysis.data.analysis"));
        WorkflowDefinition.WorkflowNode analysis = new WorkflowDefinition.WorkflowNode(
                "analysis", WorkflowDefinition.NodeType.AGENT, "analysis", "product",
                "ANALYZE_PRODUCT_DATA", Map.of(), List.of(), List.of(),
                "has scores", false, List.of(), null);
        WorkflowDefinition definition = new WorkflowDefinition(
                1, "recommendation", null, 3, List.of(analysis, node), Map.of());

        IntentGraph.IntentNode compiled = new WorkflowGraphCompiler()
                .compile(definition, "recommend")
                .getAllNodes().stream().filter(value -> "recommend".equals(value.getId()))
                .findFirst().orElseThrow();

        assertTrue(!compiled.isRequired());
        assertEquals(ExecutionPlan.MergePolicy.REPLACE, compiled.getMergePolicy());
        assertEquals("recommendation.v1", compiled.getOutputSchema());
        assertEquals(Map.of("analysis", "$.nodes.analysis.data.analysis"),
                compiled.getInputBindings());
    }
}
