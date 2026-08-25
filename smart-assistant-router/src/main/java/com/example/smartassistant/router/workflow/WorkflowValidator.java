package com.example.smartassistant.router.workflow;

import com.example.smartassistant.router.model.InputBindingExpression;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Performs deterministic validation before a workflow can be published. */
@Component
public class WorkflowValidator {

    private static final int MAX_NODES = 32;
    private static final int MAX_GRAPH_ITERATIONS = 16;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Set<String> WRITE_OPERATIONS = Set.of(
            "CREATE", "UPDATE", "DELETE", "CANCEL", "PAY", "REFUND", "SUBMIT", "CONFIRM");

    public WorkflowValidationResult validate(WorkflowDefinition definition, Set<String> allowedAgents) {
        List<WorkflowValidationResult.Violation> violations = new ArrayList<>();
        if (definition == null) {
            add(violations, "WORKFLOW_REQUIRED", "$", "workflow definition is required");
            return new WorkflowValidationResult(false, violations);
        }
        if (definition.schemaVersion() != WorkflowDefinition.CURRENT_SCHEMA_VERSION) {
            add(violations, "UNSUPPORTED_SCHEMA", "schemaVersion",
                    "supported schema version is " + WorkflowDefinition.CURRENT_SCHEMA_VERSION);
        }
        if (blank(definition.name())) add(violations, "NAME_REQUIRED", "name", "name is required");
        if (definition.maxGraphIterations() < 0
                || definition.maxGraphIterations() > MAX_GRAPH_ITERATIONS) {
            add(violations, "INVALID_ITERATION_LIMIT", "maxGraphIterations",
                    "maxGraphIterations must be between 0 and " + MAX_GRAPH_ITERATIONS);
        }
        if (definition.nodes().isEmpty()) {
            add(violations, "NODES_REQUIRED", "nodes", "at least one node is required");
        } else if (definition.nodes().size() > MAX_NODES) {
            add(violations, "TOO_MANY_NODES", "nodes", "workflow exceeds " + MAX_NODES + " nodes");
        }

        Set<String> ids = new HashSet<>();
        Map<String, List<String>> dependencies = new HashMap<>();
        Set<String> rerouteSources = new HashSet<>();
        for (int i = 0; i < definition.nodes().size(); i++) {
            WorkflowDefinition.WorkflowNode node = definition.nodes().get(i);
            String path = "nodes[" + i + "]";
            if (node == null) {
                add(violations, "NODE_REQUIRED", path, "node is required");
                continue;
            }
            if (blank(node.id()) || !SAFE_ID.matcher(node.id()).matches()) {
                add(violations, "INVALID_NODE_ID", path + ".id",
                        "node id must match " + SAFE_ID.pattern());
                continue;
            }
            if (!ids.add(node.id())) add(violations, "DUPLICATE_NODE_ID", path + ".id", node.id());
            dependencies.put(node.id(), node.dependsOn());
            if (node.type() == null) add(violations, "NODE_TYPE_REQUIRED", path + ".type", "type is required");
            if (blank(node.description())) {
                add(violations, "DESCRIPTION_REQUIRED", path + ".description", "description is required");
            }
            if (blank(node.targetAgent())) {
                add(violations, "TARGET_AGENT_REQUIRED", path + ".targetAgent", "target agent is required");
            } else if (allowedAgents != null && !allowedAgents.isEmpty()
                    && !allowedAgents.contains(node.targetAgent())) {
                add(violations, "UNKNOWN_AGENT", path + ".targetAgent", node.targetAgent());
            }
            if (node.type() == WorkflowDefinition.NodeType.HUMAN_APPROVAL
                    && !node.humanApprovalRequired()) {
                add(violations, "APPROVAL_FLAG_REQUIRED", path + ".humanApprovalRequired",
                        "HUMAN_APPROVAL nodes must require approval");
            }
            if (isWriteOperation(node.operation()) && blank(node.idempotencyKey())) {
                add(violations, "IDEMPOTENCY_KEY_REQUIRED", path + ".idempotencyKey",
                        "write operations require an idempotency key");
            }
            validateInput(node.input(), path + ".input", violations);
            validateInputBindings(node, path, violations);
            for (int c = 0; c < node.conditions().size(); c++) {
                var condition = node.conditions().get(c);
                String conditionPath = path + ".conditions[" + c + "]";
                if (condition == null || blank(condition.sourceNodeId())) {
                    add(violations, "CONDITION_SOURCE_REQUIRED", conditionPath,
                            "condition source is required");
                    continue;
                }
                if (condition.conditionType() == null) {
                    add(violations, "CONDITION_TYPE_REQUIRED", conditionPath + ".conditionType",
                            "condition type is required");
                }
                if (!blank(condition.rerouteTarget())) rerouteSources.add(node.id());
            }
        }

        validateReferences(definition, ids, violations);
        validateDag(dependencies, ids, rerouteSources, violations);
        long roots = dependencies.entrySet().stream()
                .filter(entry -> entry.getValue() == null || entry.getValue().isEmpty()).count();
        if (!definition.nodes().isEmpty() && roots == 0) {
            add(violations, "START_NODE_REQUIRED", "nodes", "workflow has no start node");
        }
        if (!rerouteSources.isEmpty() && definition.maxGraphIterations() <= 0) {
            add(violations, "LOOP_BUDGET_REQUIRED", "maxGraphIterations",
                    "conditional reroutes require a positive iteration limit");
        }
        return new WorkflowValidationResult(violations.isEmpty(), violations);
    }

    private static void validateReferences(WorkflowDefinition definition, Set<String> ids,
                                           List<WorkflowValidationResult.Violation> violations) {
        for (int i = 0; i < definition.nodes().size(); i++) {
            WorkflowDefinition.WorkflowNode node = definition.nodes().get(i);
            if (node == null || blank(node.id())) continue;
            for (String dependency : node.dependsOn()) {
                if (node.id().equals(dependency)) {
                    add(violations, "SELF_DEPENDENCY", "nodes[" + i + "].dependsOn", dependency);
                } else if (!ids.contains(dependency)) {
                    add(violations, "MISSING_DEPENDENCY", "nodes[" + i + "].dependsOn", dependency);
                }
            }
            for (int c = 0; c < node.conditions().size(); c++) {
                var condition = node.conditions().get(c);
                if (condition == null) continue;
                if (!ids.contains(condition.sourceNodeId())) {
                    add(violations, "MISSING_CONDITION_SOURCE",
                            "nodes[" + i + "].conditions[" + c + "].sourceNodeId",
                            condition.sourceNodeId());
                }
                if (!blank(condition.rerouteTarget()) && !ids.contains(condition.rerouteTarget())) {
                    add(violations, "MISSING_REROUTE_TARGET",
                            "nodes[" + i + "].conditions[" + c + "].rerouteTarget",
                            condition.rerouteTarget());
                }
            }
        }
    }

    private static void validateDag(Map<String, List<String>> dependencies, Set<String> ids,
                                    Set<String> rerouteMarkers,
                                    List<WorkflowValidationResult.Violation> violations) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        ids.forEach(id -> inDegree.put(id, 0));
        dependencies.forEach((node, deps) -> {
            if (rerouteMarkers.contains(node)) return;
            for (String dependency : deps != null ? deps : List.<String>of()) {
                if (!ids.contains(dependency)) continue;
                inDegree.merge(node, 1, Integer::sum);
                outgoing.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(node);
            }
        });
        ArrayDeque<String> queue = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> { if (degree == 0) queue.add(id); });
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            visited++;
            for (String next : outgoing.getOrDefault(current, List.of())) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) queue.add(next);
            }
        }
        if (visited != ids.size()) add(violations, "DEPENDENCY_CYCLE", "nodes",
                "ordinary dependency edges must form a DAG");
    }

    private static void validateInput(Map<String, Object> input, String path,
                                      List<WorkflowValidationResult.Violation> violations) {
        Object urlValue = input.get("url");
        if (!(urlValue instanceof String rawUrl) || blank(rawUrl)) return;
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost();
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || host == null || isPrivateAddress(host)) {
                add(violations, "UNSAFE_URL", path + ".url",
                        "workflow URLs must be public HTTP(S) endpoints");
            }
        } catch (Exception e) {
            add(violations, "INVALID_URL", path + ".url", "invalid URL");
        }
    }

    private static void validateInputBindings(
            WorkflowDefinition.WorkflowNode node, String path,
            List<WorkflowValidationResult.Violation> violations) {
        node.inputBindings().forEach((target, expression) -> {
            if (blank(target) || blank(expression)) {
                add(violations, "INVALID_INPUT_BINDING", path + ".inputBindings",
                        "binding target and expression are required");
                return;
            }
            try {
                InputBindingExpression.Parsed binding =
                        InputBindingExpression.parse(expression);
                if (!node.dependsOn().contains(binding.sourceNodeId())) {
                    add(violations, "UNDECLARED_BINDING_SOURCE",
                            path + ".inputBindings." + target,
                            "binding source must be a declared dependency");
                }
            } catch (IllegalArgumentException error) {
                add(violations, "INVALID_INPUT_BINDING",
                        path + ".inputBindings." + target, error.getMessage());
            }
        });
    }

    private static boolean isPrivateAddress(String host) {
        if ("localhost".equalsIgnoreCase(host) || host.endsWith(".local")) return true;
        if (host.equals("0.0.0.0") || host.equals("::") || host.equals("::1")) return true;
        String normalized = host.toLowerCase();
        if (normalized.startsWith("127.") || normalized.startsWith("10.")
                || normalized.startsWith("192.168.") || normalized.startsWith("169.254.")
                || normalized.startsWith("fc") || normalized.startsWith("fd")
                || normalized.startsWith("fe80:")) return true;
        if (normalized.startsWith("172.")) {
            String[] parts = normalized.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isWriteOperation(String operation) {
        if (blank(operation)) return false;
        String normalized = operation.trim().toUpperCase();
        return WRITE_OPERATIONS.stream().anyMatch(normalized::contains);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void add(List<WorkflowValidationResult.Violation> violations,
                            String code, String path, String message) {
        violations.add(new WorkflowValidationResult.Violation(code, path, message));
    }
}
