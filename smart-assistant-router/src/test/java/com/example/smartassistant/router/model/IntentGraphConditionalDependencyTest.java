package com.example.smartassistant.router.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentGraphConditionalDependencyTest {

    @Test
    void conditionalNodeIsNotExecutableBeforeItsSourceCompletes() {
        var source = new IntentGraph.IntentNode("source", "query", "general", List.of());
        var conditional = new IntentGraph.IntentNode(
                "conditional", "follow up", "general", List.of(), null,
                List.of(new IntentGraph.IntentNode.ConditionalDependency(
                        "source", IntentGraph.ConditionType.RESULT_SUCCESS, null)), false);
        IntentGraph graph = new IntentGraph("question", List.of(source, conditional));

        assertEquals(List.of("source"), graph.getExecutableNodes(Set.of(), Map.of()).stream()
                .map(IntentGraph.IntentNode::getId).toList());

        SubTaskResult success = new SubTaskResult(
                "source", "query", "general", "ok", true);
        assertEquals(List.of("conditional"), graph.getExecutableNodes(
                        Set.of("source"), Map.of("source", success)).stream()
                .map(IntentGraph.IntentNode::getId).toList());
        assertTrue(graph.hasDeadlock(Set.of("source"), Map.of()) );
    }
}
