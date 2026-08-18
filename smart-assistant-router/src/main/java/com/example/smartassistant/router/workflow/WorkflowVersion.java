package com.example.smartassistant.router.workflow;

import java.time.Instant;

public record WorkflowVersion(
        String workflowKey,
        int version,
        Status status,
        WorkflowDefinition definition,
        String checksum,
        Long createdBy,
        Instant createdAt,
        Long publishedBy,
        Instant publishedAt) {

    public enum Status {
        DRAFT,
        PUBLISHED,
        ARCHIVED
    }
}
