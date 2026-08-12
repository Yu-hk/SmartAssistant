package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.ExecutionPlan;
import com.example.smartassistant.router.model.IntentGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates model-generated plans before they can trigger downstream calls. */
final class ExecutionPlanValidator {

    private static final int MAX_NODES = 16;

    private ExecutionPlanValidator() {
    }

    static ValidationResult validate(ExecutionPlan plan) {
        if (plan == null) return ValidationResult.invalid("plan is null");
        List<String> errors = new ArrayList<>();
        if (plan.nodes().isEmpty()) errors.add("plan has no nodes");
        if (plan.nodes().size() > MAX_NODES) errors.add("plan exceeds " + MAX_NODES + " nodes");

        Set<String> ids = new HashSet<>();
        for (ExecutionPlan.TaskNode node : plan.nodes()) {
            if (node.nodeId() == null || node.nodeId().isBlank()) {
                errors.add("node id is blank");
                continue;
            }
            if (!ids.add(node.nodeId())) errors.add("duplicate node id: " + node.nodeId());
            if (node.domain() == null) errors.add("node has no domain: " + node.nodeId());
            if (node.operation() == null || node.operation().isBlank()) {
                errors.add("node has no operation: " + node.nodeId());
            }
            if (node.description() == null || node.description().isBlank()) {
                errors.add("node has no description: " + node.nodeId());
            }
            if (node.accessMode() == ExecutionPlan.AccessMode.WRITE
                    && (node.idempotencyKey() == null || node.idempotencyKey().isBlank())) {
                errors.add("write node has no idempotency key: " + node.nodeId());
            }
        }

        validateDependencies(plan.nodes().stream().collect(
                java.util.stream.Collectors.toMap(
                        ExecutionPlan.TaskNode::nodeId,
                        ExecutionPlan.TaskNode::dependsOn,
                        (left, right) -> left)), ids, errors);
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors));
    }

    static ValidationResult validateGraph(IntentGraph graph, Set<String> allowedAgents) {
        if (graph == null) return ValidationResult.invalid("graph is null");
        return validateGraphNodes(graph.getAllNodes(), allowedAgents);
    }

    static ValidationResult validateGraphNodes(Iterable<IntentGraph.IntentNode> nodes,
                                               Set<String> allowedAgents) {
        List<String> errors = new ArrayList<>();
        List<IntentGraph.IntentNode> nodeList = new ArrayList<>();
        nodes.forEach(nodeList::add);
        if (nodeList.isEmpty()) errors.add("graph has no nodes");
        if (nodeList.size() > MAX_NODES) errors.add("graph exceeds " + MAX_NODES + " nodes");
        Set<String> ids = new HashSet<>();
        Map<String, List<String>> dependencies = new HashMap<>();
        for (IntentGraph.IntentNode node : nodeList) {
            if (node.getId() == null || node.getId().isBlank()) {
                errors.add("node id is blank");
                continue;
            }
            if (!ids.add(node.getId())) errors.add("duplicate node id: " + node.getId());
            dependencies.put(node.getId(), node.getDependsOn());
            if (node.getTargetAgent() == null || node.getTargetAgent().isBlank()) {
                errors.add("node has no target agent: " + node.getId());
            } else if (allowedAgents != null && !allowedAgents.isEmpty()
                    && !allowedAgents.contains(node.getTargetAgent())) {
                errors.add("unknown target agent: " + node.getTargetAgent());
            }
        }
        validateDependencies(dependencies, ids, errors);
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors));
    }

    private static void validateDependencies(Map<String, List<String>> dependencies,
                                             Set<String> ids,
                                             List<String> errors) {
        for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
            for (String dependency : entry.getValue() != null ? entry.getValue() : List.<String>of()) {
                if (entry.getKey().equals(dependency)) {
                    errors.add("self dependency: " + entry.getKey());
                } else if (!ids.contains(dependency)) {
                    errors.add("missing dependency " + dependency + " for " + entry.getKey());
                }
            }
        }
        if (containsCycle(dependencies, ids)) errors.add("dependency cycle detected");
    }

    private static boolean containsCycle(Map<String, List<String>> dependencies, Set<String> ids) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        ids.forEach(id -> inDegree.put(id, 0));
        dependencies.forEach((node, deps) -> {
            for (String dep : deps != null ? deps : List.<String>of()) {
                if (!ids.contains(dep)) continue;
                inDegree.merge(node, 1, Integer::sum);
                outgoing.computeIfAbsent(dep, ignored -> new ArrayList<>()).add(node);
            }
        });
        ArrayDeque<String> queue = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) queue.add(id);
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            visited++;
            for (String next : outgoing.getOrDefault(id, List.of())) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) queue.add(next);
            }
        }
        return visited != ids.size();
    }

    record ValidationResult(boolean valid, List<String> errors) {
        static ValidationResult invalid(String error) {
            return new ValidationResult(false, List.of(error));
        }
    }
}
