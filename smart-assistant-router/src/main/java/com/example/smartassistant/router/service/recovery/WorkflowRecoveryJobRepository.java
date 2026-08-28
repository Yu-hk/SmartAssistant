package com.example.smartassistant.router.service.recovery;

import java.util.List;
import java.util.Optional;

public interface WorkflowRecoveryJobRepository {

    void create(WorkflowRecoveryJob job);

    Optional<WorkflowRecoveryJob> findById(String recoveryId);

    Optional<WorkflowRecoveryJob> findLatest(String requestId, long checkpointUpdatedAtEpochMs,
                                             WorkflowRecoveryTrigger trigger);

    void update(String recoveryId, WorkflowRecoveryStatus status, int attempts, String lastError);

    void complete(String recoveryId, int attempts, String result);

    default List<WorkflowRecoveryJob> findPendingNotifications(int limit) {
        return List.of();
    }

    default void markNotificationPublished(String recoveryId) {
    }
}
