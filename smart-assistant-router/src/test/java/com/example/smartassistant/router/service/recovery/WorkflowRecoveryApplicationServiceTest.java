package com.example.smartassistant.router.service.recovery;

import com.example.smartassistant.common.recovery.WorkflowRecoveryCompletedEvent;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.checkpoint.LangGraphRedisCheckpointSaver;
import com.example.smartassistant.router.service.core.LangGraphRouteExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRecoveryApplicationServiceTest {

    private LangGraphRedisCheckpointSaver checkpointSaver;
    private WorkflowRecoveryQueue queue;
    private WorkflowExecutionLeaseService leaseService;
    private LangGraphRouteExecutionService graphExecutionService;
    private InMemoryJobRepository jobs;
    private WorkflowRecoveryNotificationPublisher notificationPublisher;
    private WorkflowRecoveryApplicationService service;

    @BeforeEach
    void setUp() {
        checkpointSaver = mock(LangGraphRedisCheckpointSaver.class);
        queue = mock(WorkflowRecoveryQueue.class);
        leaseService = mock(WorkflowExecutionLeaseService.class);
        graphExecutionService = mock(LangGraphRouteExecutionService.class);
        notificationPublisher = mock(WorkflowRecoveryNotificationPublisher.class);
        jobs = new InMemoryJobRepository();
        service = new WorkflowRecoveryApplicationService(checkpointSaver, queue, leaseService,
                graphExecutionService, jobs, notificationPublisher);
    }

    @Test
    void userRequestIsQueuedButOwnerIsNotCopiedIntoTheTransportCommand() {
        candidate("request-1", 7L, false, 100L);
        when(queue.publish(any())).thenReturn(true);

        WorkflowRecoveryJob job = service.requestUserRecovery("request-1", 7L);

        assertThat(job.status()).isEqualTo(WorkflowRecoveryStatus.QUEUED);
        assertThat(job.workflowOwnerId()).isEqualTo(7L);
        ArgumentCaptor<WorkflowRecoveryCommand> command =
                ArgumentCaptor.forClass(WorkflowRecoveryCommand.class);
        verify(queue).publish(command.capture());
        assertThat(command.getValue().requestedBy()).isEqualTo(7L);
        assertThat(command.getValue().getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("userId", "workflowOwnerId");
    }

    @Test
    void userCannotRecoverAnotherUsersCheckpoint() {
        candidate("request-2", 7L, false, 100L);

        assertThatThrownBy(() -> service.requestUserRecovery("request-2", 8L))
                .isInstanceOfSatisfying(WorkflowRecoveryRejectedException.class,
                        error -> assertThat(error.reason()).isEqualTo(
                                WorkflowRecoveryRejectedException.Reason.FORBIDDEN));
        verify(queue, never()).publish(any());
    }

    @Test
    void genericRecoveryCannotBypassHumanApproval() {
        candidate("request-3", 7L, true, 100L);

        assertThatThrownBy(() -> service.requestUserRecovery("request-3", 7L))
                .isInstanceOfSatisfying(WorkflowRecoveryRejectedException.class,
                        error -> assertThat(error.reason()).isEqualTo(
                                WorkflowRecoveryRejectedException.Reason.APPROVAL_REQUIRED));
        verify(queue, never()).publish(any());
    }

    @Test
    void consumerReloadsTheOwnerFromCheckpointInsteadOfTrustingTheRequester() {
        candidate("request-4", 7L, false, 100L);
        WorkflowRecoveryCommand command = command("recovery-4", "request-4", 99L, 100L);
        jobs.create(job(command, 7L, WorkflowRecoveryStatus.QUEUED));
        when(graphExecutionService.resumeInterrupted(7L, "request-4"))
                .thenReturn(java.util.List.of(new SubTaskResult(
                        "task-1", "查询天气", "weather", "北京今天晴朗", true)));

        assertThat(service.recover(command))
                .isEqualTo(WorkflowRecoveryApplicationService.RecoveryOutcome.SUCCEEDED);

        verify(graphExecutionService).resumeInterrupted(7L, "request-4");
        assertThat(jobs.findById("recovery-4").orElseThrow().status())
                .isEqualTo(WorkflowRecoveryStatus.SUCCEEDED);
        assertThat(jobs.findById("recovery-4").orElseThrow().result())
                .isEqualTo("北京今天晴朗");
        ArgumentCaptor<WorkflowRecoveryCompletedEvent> event =
                ArgumentCaptor.forClass(WorkflowRecoveryCompletedEvent.class);
        verify(notificationPublisher).publish(event.capture());
        assertThat(event.getValue().recoveryId()).isEqualTo("recovery-4");
        assertThat(event.getValue().requestId()).isEqualTo("request-4");
        assertThat(event.getValue().userId()).isEqualTo(7L);
        assertThat(event.getValue().status()).isEqualTo("SUCCEEDED");
        assertThat(event.getValue().result()).isEqualTo("北京今天晴朗");
    }

    @Test
    void changedCheckpointGenerationIsAcknowledgedAsSuperseded() {
        when(checkpointSaver.lastUpdated("request-5")).thenReturn(OptionalLong.of(101L));
        WorkflowRecoveryCommand command = command("recovery-5", "request-5", 7L, 100L);
        jobs.create(job(command, 7L, WorkflowRecoveryStatus.QUEUED));

        assertThat(service.recover(command))
                .isEqualTo(WorkflowRecoveryApplicationService.RecoveryOutcome.SKIPPED_SUPERSEDED);
        assertThat(jobs.findById("recovery-5").orElseThrow().status())
                .isEqualTo(WorkflowRecoveryStatus.SKIPPED_SUPERSEDED);
        verify(graphExecutionService, never()).resumeInterrupted(any(), any());
    }

    @Test
    void activeRequestIsIdempotentlyReturnedWithoutRepublishing() {
        candidate("request-6", 7L, false, 100L);
        when(queue.publish(any())).thenReturn(true);

        WorkflowRecoveryJob first = service.requestUserRecovery("request-6", 7L);
        WorkflowRecoveryJob second = service.requestUserRecovery("request-6", 7L);

        assertThat(second.recoveryId()).isEqualTo(first.recoveryId());
        verify(queue).publish(any());
    }

    @Test
    void applicationServiceRepeatsAdminAuthorization() {
        assertThatThrownBy(() -> service.requestAdminRecovery(
                "request-7", 9L, "ROLE_USER", "retry", null))
                .isInstanceOfSatisfying(WorkflowRecoveryRejectedException.class,
                        error -> assertThat(error.reason()).isEqualTo(
                                WorkflowRecoveryRejectedException.Reason.FORBIDDEN));
        verify(checkpointSaver, never()).lastUpdated(any());
    }

    @Test
    void unknownQueueCommandCannotExecuteAWorkflow() {
        WorkflowRecoveryCommand command = command("unknown", "request-8", 9L, 100L);

        assertThat(service.recover(command)).isEqualTo(
                WorkflowRecoveryApplicationService.RecoveryOutcome.REJECTED_INVALID_COMMAND);
        verify(checkpointSaver, never()).lastUpdated(any());
        verify(graphExecutionService, never()).resumeInterrupted(any(), any());
    }

    private void candidate(String requestId, Long ownerId, boolean pendingApproval,
                           long checkpointVersion) {
        when(checkpointSaver.lastUpdated(requestId))
                .thenReturn(OptionalLong.of(checkpointVersion));
        when(graphExecutionService.automaticRecoveryCandidate(requestId))
                .thenReturn(Optional.of(
                        new LangGraphRouteExecutionService.AutomaticRecoveryCandidate(
                                ownerId, pendingApproval)));
    }

    private static WorkflowRecoveryCommand command(String recoveryId, String requestId,
                                                    Long requestedBy, long checkpointVersion) {
        return new WorkflowRecoveryCommand(recoveryId, requestId, checkpointVersion,
                WorkflowRecoveryTrigger.ADMIN_MANUAL, requestedBy, "test", "trace-test",
                0, System.currentTimeMillis(), null);
    }

    private static WorkflowRecoveryJob job(WorkflowRecoveryCommand command, Long owner,
                                           WorkflowRecoveryStatus status) {
        Instant now = Instant.now();
        return new WorkflowRecoveryJob(command.recoveryId(), command.requestId(),
                command.checkpointUpdatedAtEpochMs(), command.trigger(), owner,
                command.requestedBy(), command.reason(), status, command.attempts(),
                command.lastError(), null, now, now);
    }

    private static final class InMemoryJobRepository implements WorkflowRecoveryJobRepository {
        private final Map<String, WorkflowRecoveryJob> jobs = new LinkedHashMap<>();

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
            WorkflowRecoveryJob current = jobs.get(recoveryId);
            if (current == null) return;
            jobs.put(recoveryId, new WorkflowRecoveryJob(
                    current.recoveryId(), current.requestId(),
                    current.checkpointUpdatedAtEpochMs(), current.trigger(),
                    current.workflowOwnerId(), current.requestedBy(), current.reason(),
                    status, attempts, lastError, current.result(), current.requestedAt(), Instant.now()));
        }

        @Override
        public void complete(String recoveryId, int attempts, String result) {
            WorkflowRecoveryJob current = jobs.get(recoveryId);
            if (current == null) return;
            jobs.put(recoveryId, new WorkflowRecoveryJob(
                    current.recoveryId(), current.requestId(),
                    current.checkpointUpdatedAtEpochMs(), current.trigger(),
                    current.workflowOwnerId(), current.requestedBy(), current.reason(),
                    WorkflowRecoveryStatus.SUCCEEDED, attempts, null, result,
                    current.requestedAt(), Instant.now()));
        }
    }
}
