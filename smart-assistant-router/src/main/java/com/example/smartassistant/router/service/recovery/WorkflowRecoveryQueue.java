package com.example.smartassistant.router.service.recovery;

import java.time.Duration;
import java.util.Optional;

/** Transport abstraction for durable, at-least-once workflow recovery messages. */
public interface WorkflowRecoveryQueue {

    boolean publish(WorkflowRecoveryCommand command);

    Optional<WorkflowRecoveryCommand> poll();

    void acknowledge(WorkflowRecoveryCommand command);

    boolean retry(WorkflowRecoveryCommand command, String error, Duration delay);

    void deadLetter(WorkflowRecoveryCommand command, String error);

    /**
     * Reclaims messages abandoned by a crashed consumer when the transport needs application-level
     * visibility handling. Brokers with native unacknowledged-delivery recovery return zero.
     */
    default int reclaimTimedOut(Duration visibilityTimeout, int maxRetries, int limit) {
        return 0;
    }

    long readySize();

    long deadLetterSize();

}
