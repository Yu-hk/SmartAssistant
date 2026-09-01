package com.example.smartassistant.router.service.core;

/** Raised when a user has cancelled an in-flight Router workflow. */
public class WorkflowCancelledException extends RuntimeException {

    public WorkflowCancelledException(String requestId) {
        super("Workflow request was cancelled: " + requestId);
    }
}
