package com.example.smartassistant.router.service.recovery;

import com.example.smartassistant.router.service.checkpoint.LangGraphRedisCheckpointSaver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRecoveryManagerTest {

    private LangGraphRedisCheckpointSaver checkpointSaver;
    private WorkflowRecoveryQueue queue;
    private WorkflowRecoveryApplicationService recoveryService;
    private WorkflowRecoveryManager manager;

    @BeforeEach
    void setUp() {
        checkpointSaver = mock(LangGraphRedisCheckpointSaver.class);
        queue = mock(WorkflowRecoveryQueue.class);
        recoveryService = mock(WorkflowRecoveryApplicationService.class);
        when(queue.poll()).thenReturn(Optional.empty());
        manager = new WorkflowRecoveryManager(checkpointSaver, queue, recoveryService,
                1_000L, Duration.ofSeconds(2), 10, 2, 1, 10L);
        manager.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        manager.destroy();
    }

    @Test
    void scannerDelegatesEveryCandidateToTheUnifiedRecoveryEntryPoint() {
        long updatedAt = System.currentTimeMillis() - 10_000L;
        when(checkpointSaver.findStale(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new LangGraphRedisCheckpointSaver.StaleCheckpoint("stale-request", updatedAt)));
        when(recoveryService.requestAutomaticRecovery("stale-request", updatedAt)).thenReturn(true);

        assertThat(manager.scanAndPublish()).isEqualTo(1);
        verify(recoveryService).requestAutomaticRecovery("stale-request", updatedAt);
    }

    @Test
    void transientRecoveryFailureUsesBrokerDelayInsteadOfBlockingWorker() {
        var message = command("retry-request", 0);
        when(recoveryService.recover(message)).thenThrow(new IllegalStateException("temporary"));
        when(queue.retry(org.mockito.ArgumentMatchers.eq(message),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class))).thenReturn(true);

        manager.handle(message);

        verify(recoveryService).markRetryScheduled(
                org.mockito.ArgumentMatchers.eq(message),
                org.mockito.ArgumentMatchers.contains("temporary"));
        verify(queue).retry(org.mockito.ArgumentMatchers.eq(message),
                org.mockito.ArgumentMatchers.contains("temporary"),
                org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(1)));
    }

    private static WorkflowRecoveryCommand command(String requestId, int attempts) {
        return new WorkflowRecoveryCommand("recovery-1", requestId, 100L,
                WorkflowRecoveryTrigger.AUTO_STALE, null, "stale checkpoint detected",
                "trace-1", attempts, System.currentTimeMillis(), null);
    }
}
