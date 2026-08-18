package com.example.smartassistant.router.workflow;

import java.util.List;

public record WorkflowValidationResult(boolean valid, List<Violation> violations) {
    public WorkflowValidationResult {
        violations = violations != null ? List.copyOf(violations) : List.of();
    }

    public record Violation(String code, String path, String message) {
    }
}
