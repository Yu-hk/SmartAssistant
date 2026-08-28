package com.example.smartassistant.router.service.recovery;

import com.example.smartassistant.common.recovery.WorkflowRecoveryCompletedEvent;
import com.example.smartassistant.router.service.checkpoint.LangGraphRedisCheckpointSaver;
import com.example.smartassistant.router.service.core.LangGraphRouteExecutionService;
import com.example.smartassistant.router.model.SubTaskResult;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.List;
import java.util.stream.Collectors;

import static com.example.smartassistant.router.service.recovery.WorkflowRecoveryRejectedException.Reason.ACTIVE_EXECUTION;
import static com.example.smartassistant.router.service.recovery.WorkflowRecoveryRejectedException.Reason.APPROVAL_REQUIRED;
import static com.example.smartassistant.router.service.recovery.WorkflowRecoveryRejectedException.Reason.CHECKPOINT_NOT_FOUND;
import static com.example.smartassistant.router.service.recovery.WorkflowRecoveryRejectedException.Reason.CHECKPOINT_VERSION_CONFLICT;
import static com.example.smartassistant.router.service.recovery.WorkflowRecoveryRejectedException.Reason.FORBIDDEN;

/**
 * Single application boundary for automatic, user and administrator recovery requests.
 * Controllers only enqueue commands; this service is the sole caller of interrupted execution.
 */
public class WorkflowRecoveryApplicationService {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";
    private static final Logger log = LoggerFactory.getLogger(
            WorkflowRecoveryApplicationService.class);

    private final LangGraphRedisCheckpointSaver checkpointSaver;
    private final WorkflowRecoveryQueue queue;
    private final WorkflowExecutionLeaseService leaseService;
    private final LangGraphRouteExecutionService graphExecutionService;
    private final WorkflowRecoveryJobRepository jobRepository;
    private final WorkflowRecoveryNotificationPublisher notificationPublisher;

    public WorkflowRecoveryApplicationService(
            LangGraphRedisCheckpointSaver checkpointSaver,
            WorkflowRecoveryQueue queue,
            WorkflowExecutionLeaseService leaseService,
            LangGraphRouteExecutionService graphExecutionService,
            WorkflowRecoveryJobRepository jobRepository) {
        this(checkpointSaver, queue, leaseService, graphExecutionService, jobRepository,
                WorkflowRecoveryNotificationPublisher.noop());
    }

    public WorkflowRecoveryApplicationService(
            LangGraphRedisCheckpointSaver checkpointSaver,
            WorkflowRecoveryQueue queue,
            WorkflowExecutionLeaseService leaseService,
            LangGraphRouteExecutionService graphExecutionService,
            WorkflowRecoveryJobRepository jobRepository,
            WorkflowRecoveryNotificationPublisher notificationPublisher) {
        this.checkpointSaver = checkpointSaver;
        this.queue = queue;
        this.leaseService = leaseService;
        this.graphExecutionService = graphExecutionService;
        this.jobRepository = jobRepository;
        this.notificationPublisher = notificationPublisher;
    }

    public WorkflowRecoveryJob requestUserRecovery(String requestId, Long authenticatedUserId) {
        Objects.requireNonNull(authenticatedUserId, "authenticated user id is required");
        return request(requestId, WorkflowRecoveryTrigger.USER_MANUAL, authenticatedUserId,
                null, null, authenticatedUserId);
    }

    public WorkflowRecoveryJob requestAdminRecovery(String requestId, Long actorId, String role,
                                                     String reason, Long expectedCheckpointVersion) {
        Objects.requireNonNull(actorId, "administrator user id is required");
        if (!ADMIN_ROLE.equalsIgnoreCase(role)) {
            throw rejected(FORBIDDEN, "Administrator privileges are required");
        }
        return request(requestId, WorkflowRecoveryTrigger.ADMIN_MANUAL, actorId,
                reason, expectedCheckpointVersion, null);
    }

    /** Enqueues one automatic command for the supplied checkpoint generation. */
    public boolean requestAutomaticRecovery(String requestId, long checkpointUpdatedAtEpochMs) {
        if (requestId == null || requestId.isBlank()) return false;
        try {
            OptionalLong currentUpdatedAt = checkpointSaver.lastUpdated(requestId);
            if (currentUpdatedAt.isEmpty()
                    || currentUpdatedAt.getAsLong() != checkpointUpdatedAtEpochMs) {
                return false;
            }
            WorkflowRecoveryJob latest = jobRepository.findLatest(requestId,
                            checkpointUpdatedAtEpochMs, WorkflowRecoveryTrigger.AUTO_STALE)
                    .orElse(null);
            if (latest != null && (active(latest.status())
                    || latest.status() == WorkflowRecoveryStatus.SUCCEEDED
                    || latest.status() == WorkflowRecoveryStatus.DEAD_LETTERED)) {
                return false;
            }
            var candidate = candidate(requestId);
            if (candidate.pendingApproval() || leaseService.isActive(requestId)) return false;
            return createAndPublish(requestId, checkpointUpdatedAtEpochMs,
                    WorkflowRecoveryTrigger.AUTO_STALE, candidate.userId(), null,
                    "stale checkpoint detected").status() == WorkflowRecoveryStatus.QUEUED;
        } catch (WorkflowRecoveryRejectedException ignored) {
            return false;
        }
    }

    public WorkflowRecoveryJob findForUser(String recoveryId, Long authenticatedUserId) {
        Objects.requireNonNull(authenticatedUserId, "authenticated user id is required");
        WorkflowRecoveryJob job = find(recoveryId);
        if (!authenticatedUserId.equals(job.workflowOwnerId())) {
            throw rejected(FORBIDDEN, "Recovery request does not belong to the authenticated user");
        }
        return job;
    }

    public WorkflowRecoveryJob findForAdmin(String recoveryId, String role) {
        if (!ADMIN_ROLE.equalsIgnoreCase(role)) {
            throw rejected(FORBIDDEN, "Administrator privileges are required");
        }
        return find(recoveryId);
    }

    /** Executes one command after revalidating all mutable checkpoint state. */
    public RecoveryOutcome recover(WorkflowRecoveryCommand command) {
        WorkflowRecoveryJob job = jobRepository.findById(command.recoveryId()).orElse(null);
        if (job == null) return RecoveryOutcome.REJECTED_INVALID_COMMAND;
        if (!matches(job, command)) {
            update(command, WorkflowRecoveryStatus.REJECTED_INVALID_COMMAND,
                    "Recovery command does not match its persisted request");
            return RecoveryOutcome.REJECTED_INVALID_COMMAND;
        }
        if (job.status().terminal()) return RecoveryOutcome.SKIPPED_TERMINAL;

        OptionalLong currentUpdatedAt = checkpointSaver.lastUpdated(command.requestId());
        if (currentUpdatedAt.isEmpty()
                || currentUpdatedAt.getAsLong() != command.checkpointUpdatedAtEpochMs()) {
            update(command, WorkflowRecoveryStatus.SKIPPED_SUPERSEDED, null);
            return RecoveryOutcome.SKIPPED_SUPERSEDED;
        }

        var candidate = graphExecutionService.automaticRecoveryCandidate(command.requestId());
        if (candidate.isEmpty()) {
            update(command, WorkflowRecoveryStatus.SKIPPED_SUPERSEDED, null);
            return RecoveryOutcome.SKIPPED_SUPERSEDED;
        }
        if (candidate.orElseThrow().pendingApproval()) {
            update(command, WorkflowRecoveryStatus.SKIPPED_APPROVAL,
                    "Workflow is waiting for human approval");
            return RecoveryOutcome.SKIPPED_APPROVAL;
        }
        if (leaseService.isActive(command.requestId())) {
            update(command, WorkflowRecoveryStatus.SKIPPED_ACTIVE,
                    "Workflow execution lease is active");
            return RecoveryOutcome.SKIPPED_ACTIVE;
        }

        update(command, WorkflowRecoveryStatus.RECOVERING, command.lastError());
        // The owner is intentionally loaded from the checkpoint at execution time.
        List<SubTaskResult> results = graphExecutionService.resumeInterrupted(
                candidate.orElseThrow().userId(), command.requestId());
        String result = recoveredOutput(results);
        jobRepository.complete(command.recoveryId(), command.attempts(), result);
        publishCompletion(job, result);
        return RecoveryOutcome.SUCCEEDED;
    }

    public void markRetryScheduled(WorkflowRecoveryCommand command, String error) {
        jobRepository.update(command.recoveryId(), WorkflowRecoveryStatus.RETRY_SCHEDULED,
                command.attempts() + 1, error);
    }

    public void markDeadLettered(WorkflowRecoveryCommand command, String error) {
        update(command, WorkflowRecoveryStatus.DEAD_LETTERED, error);
    }

    private WorkflowRecoveryJob request(String requestId, WorkflowRecoveryTrigger trigger,
                                        Long requestedBy, String reason,
                                        Long expectedCheckpointVersion, Long requiredOwnerId) {
        requireRequestId(requestId);
        OptionalLong updatedAt = checkpointSaver.lastUpdated(requestId);
        if (updatedAt.isEmpty()) {
            throw rejected(CHECKPOINT_NOT_FOUND, "Workflow checkpoint does not exist or has expired");
        }
        if (expectedCheckpointVersion != null
                && expectedCheckpointVersion.longValue() != updatedAt.getAsLong()) {
            throw rejected(CHECKPOINT_VERSION_CONFLICT,
                    "Workflow checkpoint changed; refresh its status before recovering");
        }
        var candidate = candidate(requestId);
        if (requiredOwnerId != null && !requiredOwnerId.equals(candidate.userId())) {
            throw rejected(FORBIDDEN, "Workflow checkpoint does not belong to the authenticated user");
        }
        Optional<WorkflowRecoveryJob> latest = jobRepository.findLatest(
                requestId, updatedAt.getAsLong(), trigger);
        if (latest.isPresent() && active(latest.orElseThrow().status())) {
            return latest.orElseThrow();
        }
        if (candidate.pendingApproval()) {
            throw rejected(APPROVAL_REQUIRED,
                    "Workflow is waiting for approval; use the approval endpoint instead");
        }
        if (leaseService.isActive(requestId)) {
            throw rejected(ACTIVE_EXECUTION, "Workflow is still executing");
        }

        return createAndPublish(requestId, updatedAt.getAsLong(), trigger,
                candidate.userId(), requestedBy, reason);
    }

    private WorkflowRecoveryJob createAndPublish(String requestId, long checkpointUpdatedAtEpochMs,
                                                 WorkflowRecoveryTrigger trigger,
                                                 Long workflowOwnerId, Long requestedBy,
                                                 String reason) {
        Instant now = Instant.now();
        String recoveryId = UUID.randomUUID().toString();
        String traceId = Optional.ofNullable(MDC.get("traceId"))
                .filter(value -> !value.isBlank()).orElseGet(() -> UUID.randomUUID().toString());
        String normalizedReason = normalizeReason(reason);
        WorkflowRecoveryJob requested = new WorkflowRecoveryJob(
                recoveryId, requestId, checkpointUpdatedAtEpochMs, trigger,
                workflowOwnerId, requestedBy, normalizedReason,
                WorkflowRecoveryStatus.REQUESTED, 0, null, null, now, now);
        jobRepository.create(requested);

        WorkflowRecoveryCommand command = new WorkflowRecoveryCommand(
                recoveryId, requestId, checkpointUpdatedAtEpochMs, trigger, requestedBy,
                normalizedReason, traceId, 0, now.toEpochMilli(), null);
        WorkflowRecoveryStatus status = queue.publish(command)
                ? WorkflowRecoveryStatus.QUEUED : WorkflowRecoveryStatus.SKIPPED_DUPLICATE;
        jobRepository.update(recoveryId, status, 0, null);
        return jobRepository.findById(recoveryId).orElseGet(() -> new WorkflowRecoveryJob(
                recoveryId, requestId, checkpointUpdatedAtEpochMs, trigger,
                workflowOwnerId, requestedBy, normalizedReason, status, 0, null, null, now, now));
    }

    private LangGraphRouteExecutionService.AutomaticRecoveryCandidate candidate(String requestId) {
        return graphExecutionService.automaticRecoveryCandidate(requestId)
                .orElseThrow(() -> rejected(CHECKPOINT_NOT_FOUND,
                        "Workflow checkpoint does not exist or has expired"));
    }

    private WorkflowRecoveryJob find(String recoveryId) {
        if (recoveryId == null || recoveryId.isBlank()) {
            throw rejected(CHECKPOINT_NOT_FOUND, "Recovery request does not exist");
        }
        return jobRepository.findById(recoveryId)
                .orElseThrow(() -> rejected(CHECKPOINT_NOT_FOUND,
                        "Recovery request does not exist"));
    }

    private void update(WorkflowRecoveryCommand command, WorkflowRecoveryStatus status,
                        String error) {
        jobRepository.update(command.recoveryId(), status, command.attempts(), error);
    }

    private static boolean active(WorkflowRecoveryStatus status) {
        return status == WorkflowRecoveryStatus.REQUESTED
                || status == WorkflowRecoveryStatus.QUEUED
                || status == WorkflowRecoveryStatus.RECOVERING
                || status == WorkflowRecoveryStatus.RETRY_SCHEDULED;
    }

    private static boolean matches(WorkflowRecoveryJob job, WorkflowRecoveryCommand command) {
        return job.recoveryId().equals(command.recoveryId())
                && job.requestId().equals(command.requestId())
                && job.checkpointUpdatedAtEpochMs() == command.checkpointUpdatedAtEpochMs()
                && job.trigger() == command.trigger()
                && Objects.equals(job.requestedBy(), command.requestedBy());
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) return null;
        String normalized = reason.strip();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    /**
     * Recovery must not introduce another fallible model call after the graph has
     * completed and released its checkpoint. Assemble a deterministic response
     * from successful node outputs instead.
     */
    private static String recoveredOutput(List<SubTaskResult> results) {
        if (results == null || results.isEmpty()) return null;
        List<SubTaskResult> successful = results.stream()
                .filter(SubTaskResult::isSuccess)
                .filter(result -> result.getResult() != null && !result.getResult().isBlank())
                .toList();
        if (successful.isEmpty()) return null;
        String output = successful.size() == 1
                ? successful.getFirst().getResult().strip()
                : successful.stream()
                        .map(result -> "### " + Objects.toString(
                                result.getDescription(), "恢复结果") + "\n"
                                + result.getResult().strip())
                        .distinct()
                        .collect(Collectors.joining("\n\n"));
        return output.length() <= 20_000 ? output : output.substring(0, 20_000);
    }

    private void publishCompletion(WorkflowRecoveryJob job, String result) {
        try {
            notificationPublisher.publish(new WorkflowRecoveryCompletedEvent(
                    job.recoveryId(), job.requestId(), job.workflowOwnerId(),
                    WorkflowRecoveryStatus.SUCCEEDED.name(), result, Instant.now()));
            jobRepository.markNotificationPublished(job.recoveryId());
        } catch (RuntimeException error) {
            // The recovered workflow is already committed. Notification transport failure
            // must not replay business nodes; the polling endpoint remains the fallback.
            log.error("Failed to publish workflow recovery completion: recoveryId={}, requestId={}",
                    job.recoveryId(), job.requestId(), error);
        }
    }

    private static WorkflowRecoveryRejectedException rejected(
            WorkflowRecoveryRejectedException.Reason reason, String message) {
        return new WorkflowRecoveryRejectedException(reason, message);
    }

    public enum RecoveryOutcome {
        SUCCEEDED,
        SKIPPED_TERMINAL,
        SKIPPED_ACTIVE,
        SKIPPED_APPROVAL,
        SKIPPED_SUPERSEDED,
        REJECTED_INVALID_COMMAND
    }
}
