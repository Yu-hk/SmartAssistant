package com.example.smartassistant.router.model;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/**
 * Strongly typed execution plan produced before a request enters the DAG executor.
 *
 * <p>The current executor still consumes {@link IntentGraph}; this model is the validated
 * boundary between probabilistic task analysis and deterministic execution.</p>
 */
public record ExecutionPlan(
        String executionId,
        String originalQuestion,
        List<String> globalConstraints,
        List<TaskNode> nodes) {

    public ExecutionPlan {
        globalConstraints = globalConstraints != null ? List.copyOf(globalConstraints) : List.of();
        nodes = nodes != null ? List.copyOf(nodes) : List.of();
    }

    public IntentGraph toIntentGraph() {
        List<IntentGraph.IntentNode> graphNodes = nodes.stream()
                .map(node -> new IntentGraph.IntentNode(
                        node.nodeId(), node.description(), node.targetAgent(),
                        node.dependsOn(), node.successCriteria(), List.of(),
                        node.approvalRequired(), node.operation(), node.input(),
                        globalConstraints, node.idempotencyKey(), node.accessMode().name(),
                        node.required(), node.mergePolicy(), node.outputSchema(),
                        node.inputBindings()))
                .toList();
        return new IntentGraph(originalQuestion, graphNodes);
    }

    public record TaskNode(
            String nodeId,
            String targetAgent,
            String operation,
            String description,
            Map<String, Object> input,
            List<String> dependsOn,
            AccessMode accessMode,
            List<String> requiredSlots,
            String idempotencyKey,
            boolean approvalRequired,
            String successCriteria,
            MergePolicy mergePolicy,
            boolean required,
            String outputSchema,
            Map<String, String> inputBindings) {

        /** Compatibility constructor used by callers that still reference a built-in domain. */
        public TaskNode(
                String nodeId, Domain domain, String operation, String description,
                Map<String, Object> input, List<String> dependsOn, AccessMode accessMode,
                List<String> requiredSlots, String idempotencyKey, boolean approvalRequired,
                String successCriteria, MergePolicy mergePolicy) {
            this(nodeId, domain != null ? domain.agentName() : null,
                    operation, description, input, dependsOn, accessMode,
                    requiredSlots, idempotencyKey, approvalRequired, successCriteria,
                    mergePolicy, true, null, Map.of());
        }

        /** Full compatibility constructor for the pre-dynamic-Agent record shape. */
        public TaskNode(
                String nodeId, Domain domain, String operation, String description,
                Map<String, Object> input, List<String> dependsOn, AccessMode accessMode,
                List<String> requiredSlots, String idempotencyKey, boolean approvalRequired,
                String successCriteria, MergePolicy mergePolicy, boolean required,
                String outputSchema, Map<String, String> inputBindings) {
            this(nodeId, domain != null ? domain.agentName() : null,
                    operation, description, input, dependsOn, accessMode,
                    requiredSlots, idempotencyKey, approvalRequired, successCriteria,
                    mergePolicy, required, outputSchema, inputBindings);
        }

        public TaskNode {
            targetAgent = targetAgent == null || targetAgent.isBlank()
                    ? null : targetAgent.trim();
            // Entity maps legitimately contain null values for absent slots; Map.copyOf rejects
            // those values, so keep a defensive unmodifiable LinkedHashMap instead.
            input = input != null
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(input)) : Map.of();
            dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
            requiredSlots = requiredSlots != null ? List.copyOf(requiredSlots) : List.of();
            accessMode = accessMode != null ? accessMode : AccessMode.READ;
            mergePolicy = mergePolicy != null ? mergePolicy : MergePolicy.APPEND;
            outputSchema = outputSchema == null || outputSchema.isBlank()
                    ? null : outputSchema.trim();
            inputBindings = inputBindings != null
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(inputBindings)) : Map.of();
        }

        /** Built-in domain view retained for source compatibility; dynamic Agents return null. */
        public Domain domain() {
            return Domain.fromAgentName(targetAgent);
        }
    }

    public enum Domain {
        PRODUCT("product"),
        ORDER("order"),
        GENERAL("general"),
        BUILTIN_ORDER_PREPARATION("builtin_order_preparation");

        private final String agentName;

        Domain(String agentName) {
            this.agentName = agentName;
        }

        public String agentName() {
            return agentName;
        }

        public static Domain fromAgentName(String agentName) {
            if (agentName == null || agentName.isBlank()) return null;
            for (Domain domain : values()) {
                if (domain.agentName.equalsIgnoreCase(agentName.trim())) return domain;
            }
            return null;
        }
    }

    public enum AccessMode {
        READ,
        WRITE
    }

    public enum MergePolicy {
        APPEND,
        REPLACE,
        STRUCTURED
    }
}
