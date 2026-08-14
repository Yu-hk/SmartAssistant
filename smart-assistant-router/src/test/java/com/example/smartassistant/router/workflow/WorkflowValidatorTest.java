package com.example.smartassistant.router.workflow;

import com.example.smartassistant.router.model.IntentGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowValidatorTest {

    private final WorkflowValidator validator = new WorkflowValidator();

    @Test
    void acceptsSafeVersionOneWorkflow() {
        WorkflowDefinition definition = workflow(List.of(
                node("search", "product", "QUERY", List.of(), false, null, List.of()),
                node("order", "order", "CREATE_ORDER", List.of("search"), true,
                        "order-${requestId}", List.of())));

        var result = validator.validate(definition, Set.of("product", "order"));

        assertTrue(result.valid(), () -> result.violations().toString());
    }

    @Test
    void rejectsUnknownAgentsUnsafeUrlsAndWriteWithoutIdempotency() {
        WorkflowDefinition.WorkflowNode unsafe = new WorkflowDefinition.WorkflowNode(
                "call", WorkflowDefinition.NodeType.AGENT, "call internal endpoint", "missing",
                "CREATE", Map.of("url", "http://127.0.0.1:8080/admin"), List.of(), List.of(),
                null, false, List.of(), null);

        var result = validator.validate(workflow(List.of(unsafe)), Set.of("product"));

        assertFalse(result.valid());
        assertTrue(has(result, "UNKNOWN_AGENT"));
        assertTrue(has(result, "UNSAFE_URL"));
        assertTrue(has(result, "IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void requiresLoopBudgetForConditionalReroute() {
        var condition = new WorkflowDefinition.ConditionalEdge(
                "search", IntentGraph.ConditionType.RESULT_FAILED, null, "search");
        WorkflowDefinition definition = new WorkflowDefinition(1, "retry", null, 0,
                List.of(node("search", "product", "QUERY", List.of(), false, null, List.of()),
                        node("retry", "product", "QUERY", List.of("search"), false, null,
                                List.of(condition))), Map.of());

        var result = validator.validate(definition, Set.of("product"));

        assertFalse(result.valid());
        assertTrue(has(result, "LOOP_BUDGET_REQUIRED"));
    }

    private static boolean has(WorkflowValidationResult result, String code) {
        return result.violations().stream().anyMatch(item -> code.equals(item.code()));
    }

    private static WorkflowDefinition workflow(List<WorkflowDefinition.WorkflowNode> nodes) {
        return new WorkflowDefinition(1, "commerce", "commerce flow", 4, nodes, Map.of());
    }

    private static WorkflowDefinition.WorkflowNode node(
            String id, String agent, String operation, List<String> dependencies,
            boolean approval, String idempotencyKey,
            List<WorkflowDefinition.ConditionalEdge> conditions) {
        return new WorkflowDefinition.WorkflowNode(id,
                approval ? WorkflowDefinition.NodeType.HUMAN_APPROVAL : WorkflowDefinition.NodeType.AGENT,
                "execute " + id, agent, operation, Map.of(), dependencies, conditions,
                "non-empty result", approval, List.of(), idempotencyKey);
    }
}
