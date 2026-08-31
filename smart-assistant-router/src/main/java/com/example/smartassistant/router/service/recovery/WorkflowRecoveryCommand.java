package com.example.smartassistant.router.service.recovery;

/**
 * Transport command for at-least-once recovery. The workflow owner is deliberately absent:
 * consumers must derive it from the durable checkpoint instead of trusting message input.
 */
public record WorkflowRecoveryCommand(
        String recoveryId,
        String requestId,
        long checkpointUpdatedAtEpochMs,
        WorkflowRecoveryTrigger trigger,
        Long requestedBy,
        String reason,
        String traceId,
        int attempts,
        long enqueuedAtEpochMs,
        String lastError) {

    public WorkflowRecoveryCommand nextAttempt(String error) {
        return new WorkflowRecoveryCommand(recoveryId, requestId, checkpointUpdatedAtEpochMs,
                trigger, requestedBy, reason, traceId, attempts + 1, enqueuedAtEpochMs, error);
    }

    public WorkflowRecoveryCommand withError(String error) {
        return new WorkflowRecoveryCommand(recoveryId, requestId, checkpointUpdatedAtEpochMs,
                trigger, requestedBy, reason, traceId, attempts, enqueuedAtEpochMs, error);
    }
}
