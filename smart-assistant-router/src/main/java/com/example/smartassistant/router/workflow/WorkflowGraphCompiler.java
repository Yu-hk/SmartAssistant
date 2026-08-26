package com.example.smartassistant.router.workflow;

import com.example.smartassistant.router.model.IntentGraph;
import org.springframework.stereotype.Component;

import java.util.List;

/** Compiles the stable workflow DSL into the IntentGraph consumed by LangGraph4j. */
@Component
public class WorkflowGraphCompiler {

    public IntentGraph compile(WorkflowDefinition definition, String question) {
        if (definition == null) throw new IllegalArgumentException("workflow definition is required");
        List<IntentGraph.IntentNode> nodes = definition.nodes().stream()
                .map(node -> new IntentGraph.IntentNode(
                        node.id(),
                        node.description(),
                        node.targetAgent(),
                        node.dependsOn(),
                        node.successCriteria(),
                        node.conditions().stream()
                                .map(condition -> new IntentGraph.IntentNode.ConditionalDependency(
                                        condition.sourceNodeId(), condition.conditionType(),
                                        condition.conditionValue(), condition.rerouteTarget()))
                                .toList(),
                        node.humanApprovalRequired()
                                || node.type() == WorkflowDefinition.NodeType.HUMAN_APPROVAL,
                        node.operation(), node.input(), node.constraints(), node.idempotencyKey(),
                        !Boolean.FALSE.equals(node.required()), node.mergePolicy(),
                        node.outputSchema(), node.inputBindings()))
                .toList();
        return new IntentGraph(question != null ? question : definition.name(), nodes,
                definition.maxGraphIterations());
    }

    /** Compiles a published immutable snapshot while retaining its runtime identity. */
    public IntentGraph compile(WorkflowVersion version, String question) {
        IntentGraph graph = compile(version.definition(), question);
        return new IntentGraph(graph.getQuestion(), List.copyOf(graph.getAllNodes()),
                version.definition().maxGraphIterations(), version.workflowKey(),
                version.version(), version.checksum());
    }
}
