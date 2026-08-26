package com.example.smartassistant.router.workflow;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.ExecutionPlan;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned, declarative workflow definition accepted by the management API.
 * Published definitions are immutable snapshots; editing creates a new draft version.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record WorkflowDefinition(
        int schemaVersion,
        String name,
        String description,
        int maxGraphIterations,
        List<WorkflowNode> nodes,
        Map<String, String> labels) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WorkflowDefinition {
        nodes = nodes != null ? List.copyOf(nodes) : List.of();
        labels = immutableMap(labels);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return source == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record WorkflowNode(
            String id,
            NodeType type,
            String description,
            String targetAgent,
            String operation,
            Map<String, Object> input,
            List<String> dependsOn,
            List<ConditionalEdge> conditions,
            String successCriteria,
            boolean humanApprovalRequired,
            List<String> constraints,
            String idempotencyKey,
            Boolean required,
            ExecutionPlan.MergePolicy mergePolicy,
            String outputSchema,
            Map<String, String> inputBindings) {

        /** Compatibility constructor for schema-v1 definitions without data contracts. */
        public WorkflowNode(
                String id, NodeType type, String description, String targetAgent,
                String operation, Map<String, Object> input, List<String> dependsOn,
                List<ConditionalEdge> conditions, String successCriteria,
                boolean humanApprovalRequired, List<String> constraints,
                String idempotencyKey) {
            this(id, type, description, targetAgent, operation, input, dependsOn,
                    conditions, successCriteria, humanApprovalRequired, constraints,
                    idempotencyKey, true, ExecutionPlan.MergePolicy.APPEND, null, Map.of());
        }

        public WorkflowNode {
            input = immutableMap(input);
            dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
            conditions = conditions != null ? List.copyOf(conditions) : List.of();
            constraints = constraints != null ? List.copyOf(constraints) : List.of();
            required = required == null ? Boolean.TRUE : required;
            mergePolicy = mergePolicy != null
                    ? mergePolicy : ExecutionPlan.MergePolicy.APPEND;
            outputSchema = outputSchema == null || outputSchema.isBlank()
                    ? null : outputSchema.trim();
            inputBindings = immutableMap(inputBindings);
        }
    }

    /** Only safe, governed node kinds are publishable. */
    public enum NodeType {
        AGENT,
        HUMAN_APPROVAL
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConditionalEdge(
            String sourceNodeId,
            IntentGraph.ConditionType conditionType,
            String conditionValue,
            String rerouteTarget) {
    }
}
