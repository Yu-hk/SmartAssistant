package com.example.smartassistant.router.service.recovery;

/** Durable lifecycle of one recovery request. */
public enum WorkflowRecoveryStatus {
    REQUESTED,
    QUEUED,
    RECOVERING,
    RETRY_SCHEDULED,
    SUCCEEDED,
    DEAD_LETTERED,
    SKIPPED_ACTIVE,
    SKIPPED_APPROVAL,
    SKIPPED_SUPERSEDED,
    SKIPPED_DUPLICATE,
    REJECTED_INVALID_COMMAND;

    public boolean terminal() {
        return switch (this) {
            case SUCCEEDED, DEAD_LETTERED, SKIPPED_ACTIVE, SKIPPED_APPROVAL,
                    SKIPPED_SUPERSEDED, SKIPPED_DUPLICATE, REJECTED_INVALID_COMMAND -> true;
            default -> false;
        };
    }
}
