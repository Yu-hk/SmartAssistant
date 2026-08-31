package com.example.smartassistant.router.service.recovery;

import com.example.smartassistant.common.recovery.WorkflowRecoveryCompletedEvent;

/** Publishes terminal recovery events without coupling the application service to AMQP. */
@FunctionalInterface
public interface WorkflowRecoveryNotificationPublisher {

    void publish(WorkflowRecoveryCompletedEvent event);

    static WorkflowRecoveryNotificationPublisher noop() {
        return event -> { };
    }
}
