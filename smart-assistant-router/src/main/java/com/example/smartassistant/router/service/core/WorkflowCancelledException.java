package com.example.smartassistant.router.service.core;

/** Raised when a user has cancelled an in-flight Router workflow. */
public class WorkflowCancelledException extends RuntimeException {

    public WorkflowCancelledException(String requestId) {
        super("Workflow request was cancelled: " + requestId);
    }

    /** Only a typed user-cancellation signal is cancellation, not any timeout/interruption. */
    public static WorkflowCancelledException findIn(Throwable failure) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (int depth = 0; failure != null && depth < 32 && seen.add(failure); depth++, failure = failure.getCause()) {
            if (failure instanceof WorkflowCancelledException cancelled) return cancelled;
        }
        return null;
    }
}
