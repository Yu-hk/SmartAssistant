package com.example.smartassistant.router.service.recovery;

import java.time.Instant;

/** Persisted recovery request exposed by the user and administrator status APIs. */
public record WorkflowRecoveryJob(
        String recoveryId,
        String requestId,
        long checkpointUpdatedAtEpochMs,
        WorkflowRecoveryTrigger trigger,
        Long workflowOwnerId,
        Long requestedBy,
        String reason,
        WorkflowRecoveryStatus status,
        int attempts,
        String lastError,
        String result,
        Instant requestedAt,
        Instant updatedAt) {
}
