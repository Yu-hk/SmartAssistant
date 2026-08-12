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

    private static ExecutionPlan.TaskNode node(String id, List<String> dependencies) {
        return new ExecutionPlan.TaskNode(
                id, ExecutionPlan.Domain.GENERAL, "GENERAL_QUERY", "task " + id,
                Map.of(), dependencies, ExecutionPlan.AccessMode.READ, List.of(), null,
                false, null, ExecutionPlan.MergePolicy.APPEND);
    }
}
