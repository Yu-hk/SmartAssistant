package com.example.smartassistant.router.workflow;

import java.util.List;
import java.util.Optional;

public interface WorkflowVersionRepository {
    WorkflowVersion createDraft(String workflowKey, WorkflowDefinition definition, Long createdBy);
    Optional<WorkflowVersion> find(String workflowKey, int version);
    Optional<WorkflowVersion> findPublished(String workflowKey);
    List<WorkflowVersion> list(String workflowKey);
    boolean publish(String workflowKey, int version, String checksum, Long publishedBy);
}
