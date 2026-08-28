package com.example.smartassistant.router.service.recovery;

import com.example.smartassistant.common.recovery.WorkflowRecoveryCompletedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRecoveryNotificationOutboxPublisherTest {

    @Test
    void confirmedEventIsMarkedPublished() {
        WorkflowRecoveryJobRepository jobs = mock(WorkflowRecoveryJobRepository.class);
        WorkflowRecoveryNotificationPublisher publisher =
                mock(WorkflowRecoveryNotificationPublisher.class);
        WorkflowRecoveryJob job = job("recovery-1");
        when(jobs.findPendingNotifications(50)).thenReturn(List.of(job));

        new WorkflowRecoveryNotificationOutboxPublisher(jobs, publisher).publishPending();

        ArgumentCaptor<WorkflowRecoveryCompletedEvent> event =
                ArgumentCaptor.forClass(WorkflowRecoveryCompletedEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(7L);
        assertThat(event.getValue().result()).isEqualTo("恢复结果");
        verify(jobs).markNotificationPublished("recovery-1");
    }

    @Test
    void failedPublishRemainsPendingForTheNextScan() {
        WorkflowRecoveryJobRepository jobs = mock(WorkflowRecoveryJobRepository.class);
        WorkflowRecoveryNotificationPublisher publisher =
                mock(WorkflowRecoveryNotificationPublisher.class);
        WorkflowRecoveryJob job = job("recovery-2");
        when(jobs.findPendingNotifications(50)).thenReturn(List.of(job));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher).publish(org.mockito.ArgumentMatchers.any());

        new WorkflowRecoveryNotificationOutboxPublisher(jobs, publisher).publishPending();

        verify(jobs, never()).markNotificationPublished("recovery-2");
    }

    private static WorkflowRecoveryJob job(String recoveryId) {
        Instant now = Instant.parse("2026-08-27T08:00:00Z");
        return new WorkflowRecoveryJob(recoveryId, "request-1", 10L,
                WorkflowRecoveryTrigger.AUTO_STALE, 7L, null, null,
                WorkflowRecoveryStatus.SUCCEEDED, 0, null, "恢复结果", now, now);
    }
}
