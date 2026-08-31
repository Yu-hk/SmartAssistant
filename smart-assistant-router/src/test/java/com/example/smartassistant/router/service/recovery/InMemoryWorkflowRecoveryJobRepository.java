package com.example.smartassistant.router.service.recovery;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Lightweight durable-store substitute for recovery integration tests. */
public class InMemoryWorkflowRecoveryJobRepository implements WorkflowRecoveryJobRepository {

    private final Map<String, WorkflowRecoveryJob> jobs = new ConcurrentHashMap<>();

    @Override
    public void create(WorkflowRecoveryJob job) {
        jobs.put(job.recoveryId(), job);
    }

    @Override
    public Optional<WorkflowRecoveryJob> findById(String recoveryId) {
        return Optional.ofNullable(jobs.get(recoveryId));
    }

    @Override
    public Optional<WorkflowRecoveryJob> findLatest(
            String requestId, long checkpointUpdatedAtEpochMs,
            WorkflowRecoveryTrigger trigger) {
        return jobs.values().stream()
                .filter(job -> job.requestId().equals(requestId))
                .filter(job -> job.checkpointUpdatedAtEpochMs() == checkpointUpdatedAtEpochMs)
                .filter(job -> job.trigger() == trigger)
                .max(Comparator.comparing(WorkflowRecoveryJob::requestedAt));
    }

    @Override
    public void update(String recoveryId, WorkflowRecoveryStatus status,
                       int attempts, String lastError) {
        jobs.computeIfPresent(recoveryId, (id, current) -> new WorkflowRecoveryJob(
                current.recoveryId(), current.requestId(),
                current.checkpointUpdatedAtEpochMs(), current.trigger(),
                current.workflowOwnerId(), current.requestedBy(), current.reason(),
                status, attempts, lastError, current.result(), current.requestedAt(), Instant.now()));
    }

    @Override
    public void complete(String recoveryId, int attempts, String result) {
        jobs.computeIfPresent(recoveryId, (id, current) -> new WorkflowRecoveryJob(
                current.recoveryId(), current.requestId(),
                current.checkpointUpdatedAtEpochMs(), current.trigger(),
                current.workflowOwnerId(), current.requestedBy(), current.reason(),
                WorkflowRecoveryStatus.SUCCEEDED, attempts, null, result,
                current.requestedAt(), Instant.now()));
    }
}
