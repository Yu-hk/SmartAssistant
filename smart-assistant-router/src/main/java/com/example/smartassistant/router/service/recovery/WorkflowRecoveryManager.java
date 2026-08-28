package com.example.smartassistant.router.service.recovery;

import com.example.smartassistant.router.service.checkpoint.LangGraphRedisCheckpointSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Scans stale checkpoints and consumes their recovery messages. */
public class WorkflowRecoveryManager implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRecoveryManager.class);

    private final LangGraphRedisCheckpointSaver checkpointSaver;
    private final WorkflowRecoveryQueue queue;
    private final WorkflowRecoveryApplicationService recoveryService;
    private final long staleAfterMs;
    private final Duration visibilityTimeout;
    private final int scanBatchSize;
    private final int maxRetries;
    private final int workerCount;
    private final long idlePollMs;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<ExecutorService> workerExecutors = new ArrayList<>();

    public WorkflowRecoveryManager(LangGraphRedisCheckpointSaver checkpointSaver,
                                   WorkflowRecoveryQueue queue,
                                   WorkflowRecoveryApplicationService recoveryService,
                                   long staleAfterMs, Duration visibilityTimeout,
                                   int scanBatchSize, int maxRetries,
                                   int workerCount, long idlePollMs) {
        this.checkpointSaver = checkpointSaver;
        this.queue = queue;
        this.recoveryService = recoveryService;
        this.staleAfterMs = Math.max(1_000L, staleAfterMs);
        this.visibilityTimeout = visibilityTimeout;
        this.scanBatchSize = Math.max(1, scanBatchSize);
        this.maxRetries = Math.max(0, maxRetries);
        this.workerCount = Math.max(1, workerCount);
        this.idlePollMs = Math.max(10L, idlePollMs);
    }

    /** Every Router instance may scan; queue publication deduplicates checkpoint generations. */
    @Scheduled(fixedDelayString = "${router.graph.recovery.scan-interval-ms:30000}")
    public int scanAndPublish() {
        if (!running.get()) return 0;
        int reclaimed = queue.reclaimTimedOut(visibilityTimeout, maxRetries, scanBatchSize);
        if (reclaimed > 0) {
            log.warn("[WorkflowRecovery] reclaimed abandoned recovery deliveries: count={}", reclaimed);
        }

        long cutoff = System.currentTimeMillis() - staleAfterMs;
        int published = 0;
        for (var stale : checkpointSaver.findStale(cutoff, scanBatchSize)) {
            try {
                if (recoveryService.requestAutomaticRecovery(
                        stale.threadId(), stale.updatedAtEpochMs())) {
                    published++;
                    log.warn("[WorkflowRecovery] stale workflow queued: requestId={}, updatedAt={}",
                            stale.threadId(), stale.updatedAtEpochMs());
                }
            } catch (RuntimeException e) {
                log.warn("[WorkflowRecovery] stale checkpoint inspection failed: requestId={}, error={}",
                        stale.threadId(), e.getMessage());
            }
        }
        return published;
    }

    @Override
    public synchronized void afterPropertiesSet() {
        if (!running.compareAndSet(false, true)) return;
        for (int i = 0; i < workerCount; i++) {
            ExecutorService executor = Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("workflow-recovery-worker-" + i).factory());
            workerExecutors.add(executor);
            executor.submit(this::consumeLoop);
        }
        log.info("[WorkflowRecovery] automatic recovery started: workers={}, staleAfterMs={}",
                workerCount, staleAfterMs);
    }

    private void consumeLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                var delivery = queue.poll();
                if (delivery.isEmpty()) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(idlePollMs));
                    continue;
                }
                handle(delivery.orElseThrow());
            } catch (RuntimeException e) {
                if (!running.get()) break;
                log.warn("[WorkflowRecovery] consumer poll failed: {}", e.getMessage());
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(idlePollMs));
            }
        }
    }

    void handle(WorkflowRecoveryCommand message) {
        try {
            var outcome = recoveryService.recover(message);
            queue.acknowledge(message);
            log.info("[WorkflowRecovery] recovery command completed: requestId={}, recoveryId={}, outcome={}, attempt={}",
                    message.requestId(), message.recoveryId(), outcome, message.attempts());
        } catch (RuntimeException e) {
            retryOrDeadLetter(message, e);
        }
    }

    private void retryOrDeadLetter(WorkflowRecoveryCommand message, RuntimeException error) {
        String reason = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (message.attempts() < maxRetries) {
            long backoffMs = Math.min(30_000L, 1_000L << Math.min(message.attempts(), 5));
            if (!queue.retry(message, reason, Duration.ofMillis(backoffMs))) {
                throw new IllegalStateException("Recovery retry command was not accepted by the broker");
            }
            recoveryService.markRetryScheduled(message, reason);
            log.warn("[WorkflowRecovery] workflow recovery retry queued: requestId={}, attempt={}, error={}",
                    message.requestId(), message.attempts() + 1, reason);
        } else {
            queue.deadLetter(message, reason);
            recoveryService.markDeadLettered(message, reason);
            log.error("[WorkflowRecovery] workflow recovery moved to dead letter: requestId={}, error={}",
                    message.requestId(), reason);
        }
    }

    @Override
    public synchronized void destroy() {
        if (!running.compareAndSet(true, false)) return;
        for (ExecutorService executor : workerExecutors) executor.shutdownNow();
        workerExecutors.clear();
    }
}
