package com.example.smartassistant.common.recovery;

import java.time.Instant;

/** Durable domain event emitted after an interrupted workflow is recovered. */
public record WorkflowRecoveryCompletedEvent(
        String recoveryId,
        String requestId,
        Long userId,
        String status,
        String result,
        Instant completedAt) {
}
