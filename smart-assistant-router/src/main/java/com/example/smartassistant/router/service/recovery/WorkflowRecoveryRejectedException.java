package com.example.smartassistant.router.service.recovery;

/** Expected rejection returned to recovery API callers without executing the graph. */
public class WorkflowRecoveryRejectedException extends RuntimeException {

    private final Reason reason;

    public WorkflowRecoveryRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        CHECKPOINT_NOT_FOUND,
        FORBIDDEN,
        APPROVAL_REQUIRED,
        ACTIVE_EXECUTION,
        CHECKPOINT_VERSION_CONFLICT
    }
}
