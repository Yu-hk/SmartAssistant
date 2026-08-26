package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.ExecutionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPlanValidatorTest {

    @Test
    void rejectsMissingDependency() {
        ExecutionPlan plan = new ExecutionPlan("req", "question", List.of(), List.of(
                node("t1", List.of("missing"))));

        var result = ExecutionPlanValidator.validate(plan);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("missing dependency")));
    }

    @Test
    void rejectsDependencyCycle() {
        ExecutionPlan plan = new ExecutionPlan("req", "question", List.of(), List.of(
                node("t1", List.of("t2")),
                node("t2", List.of("t1"))));

        var result = ExecutionPlanValidator.validate(plan);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("dependency cycle detected"));
    }

    @Test
    void acceptsValidatedDag() {
        ExecutionPlan plan = new ExecutionPlan("req", "question", List.of(), List.of(
                node("t1", List.of()),
                node("t2", List.of("t1"))));

        assertTrue(ExecutionPlanValidator.validate(plan).valid());
    }

    @Test
    void acceptsBindingToWholeStructuredPredecessorData() {
        ExecutionPlan.TaskNode consumer = new ExecutionPlan.TaskNode(
                "consumer", ExecutionPlan.Domain.PRODUCT, "ANALYZE_PRODUCT_DATA",
                "analyze candidates", Map.of(), List.of("source"),
                ExecutionPlan.AccessMode.READ, List.of(), null, false, null,
                ExecutionPlan.MergePolicy.STRUCTURED, true, "analysis.v1",
                Map.of("candidateData", "$.nodes.source.data"));
        ExecutionPlan plan = new ExecutionPlan("req", "question", List.of(), List.of(
                new ExecutionPlan.TaskNode(
                        "source", ExecutionPlan.Domain.PRODUCT, "DISCOVER_PRODUCTS",
                        "discover products", Map.of(), List.of(), ExecutionPlan.AccessMode.READ,
                        List.of(), null, false, null, ExecutionPlan.MergePolicy.STRUCTURED),
                consumer));

        assertTrue(ExecutionPlanValidator.validate(plan).valid());
    }

    @Test
    void rejectsBindingSourceThatIsNotADeclaredDependency() {
        ExecutionPlan.TaskNode invalid = new ExecutionPlan.TaskNode(
                "consumer", ExecutionPlan.Domain.GENERAL, "ANSWER", "consume result",
                Map.of(), List.of("source"), ExecutionPlan.AccessMode.READ, List.of(), null,
                false, null, ExecutionPlan.MergePolicy.STRUCTURED, true, "answer.v1",
                Map.of("facts", "$.nodes.other.data.facts"));
        ExecutionPlan plan = new ExecutionPlan("req", "question", List.of(), List.of(
                node("source", List.of()), node("other", List.of()), invalid));

        var result = ExecutionPlanValidator.validate(plan);

        assertFalse(result.valid());
        assertTrue(result.errors().stream()
                .anyMatch(error -> error.contains("binding source other")));
    }

    @Test
    void rejectsBindingSyntaxThatRuntimeCannotResolve() {
        ExecutionPlan.TaskNode invalid = new ExecutionPlan.TaskNode(
                "consumer", ExecutionPlan.Domain.GENERAL, "ANSWER", "consume result",
                Map.of(), List.of("source"), ExecutionPlan.AccessMode.READ, List.of(), null,
                false, null, ExecutionPlan.MergePolicy.STRUCTURED, true, "answer.v1",
                Map.of("facts", "$.nodes.source.payload.facts"));
        ExecutionPlan plan = new ExecutionPlan("req", "question", List.of(), List.of(
                node("source", List.of()), invalid));

        var result = ExecutionPlanValidator.validate(plan);

        assertFalse(result.valid());
        assertTrue(result.errors().stream()
                .anyMatch(error -> error.contains("unsupported binding section")));
    }

    private static ExecutionPlan.TaskNode node(String id, List<String> dependencies) {
        return new ExecutionPlan.TaskNode(
                id, ExecutionPlan.Domain.GENERAL, "GENERAL_QUERY", "task " + id,
                Map.of(), dependencies, ExecutionPlan.AccessMode.READ, List.of(), null,
                false, null, ExecutionPlan.MergePolicy.APPEND);
    }
}
