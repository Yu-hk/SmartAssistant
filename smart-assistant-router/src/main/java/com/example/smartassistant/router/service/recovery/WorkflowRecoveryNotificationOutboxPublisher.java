package com.example.smartassistant.router.service.recovery;

import com.example.smartassistant.common.recovery.WorkflowRecoveryCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Retries unconfirmed recovery notifications without replaying business nodes. */
public class WorkflowRecoveryNotificationOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(
            WorkflowRecoveryNotificationOutboxPublisher.class);
    private final WorkflowRecoveryJobRepository jobRepository;
    private final WorkflowRecoveryNotificationPublisher publisher;

    public WorkflowRecoveryNotificationOutboxPublisher(
            WorkflowRecoveryJobRepository jobRepository,
            WorkflowRecoveryNotificationPublisher publisher) {
        this.jobRepository = jobRepository;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString =
            "${router.graph.recovery.notification-retry-interval-ms:10000}")
    public void publishPending() {
        for (WorkflowRecoveryJob job : jobRepository.findPendingNotifications(50)) {
            try {
                publisher.publish(new WorkflowRecoveryCompletedEvent(
                        job.recoveryId(), job.requestId(), job.workflowOwnerId(),
                        WorkflowRecoveryStatus.SUCCEEDED.name(), job.result(), job.updatedAt()));
                jobRepository.markNotificationPublished(job.recoveryId());
            } catch (RuntimeException error) {
                log.warn("Recovery notification remains pending: recoveryId={}, requestId={}",
                        job.recoveryId(), job.requestId(), error);
            }
        }
    }
}
