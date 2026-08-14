package com.example.smartassistant.router.workflow;

import com.example.smartassistant.router.model.IntentGraph;
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
}
