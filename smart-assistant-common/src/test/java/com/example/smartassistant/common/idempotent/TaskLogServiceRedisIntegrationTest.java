package com.example.smartassistant.common.idempotent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "TASK_LOG_REDIS_PORT", matches = "\\d+")
class TaskLogServiceRedisIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @Test
    void atomicallyDeduplicatesConcurrentExecution() throws Exception {
        TaskLogService first = service(5_000L);
        TaskLogService second = new TaskLogService(redis, new DistributedLock(redis), 5_000L);
        String requestId = "task-log-concurrent";
        delete(requestId);
        AtomicInteger effects = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                return first.executeIfNotDone(requestId, "order:create", "order:create:1", () -> {
                    effects.incrementAndGet();
                    sleep(150L);
                    return "created";
                });
            });
            var two = executor.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                return second.executeIfNotDone(requestId, "order:create", "order:create:1", () -> {
                    effects.incrementAndGet();
                    sleep(150L);
                    return "created";
                });
            });
            start.countDown();
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
        }

        assertThat(effects).hasValue(1);
        assertThat(first.executeIfNotDone(requestId, "order:create", "order:create:1", () -> {
            effects.incrementAndGet();
            return "duplicate";
        })).isEqualTo("created");
        assertThat(effects).hasValue(1);
    }

    @Test
    void expiredLeaseIsTakenOverWithHigherFencingToken() {
        TaskLogService crashedWorker = service(1_000L);
        TaskLogService replacement = new TaskLogService(redis, new DistributedLock(redis), 1_000L);
        String requestId = "task-log-takeover";
        delete(requestId);

        TaskLogService.TaskLog abandoned = crashedWorker.tryStart(requestId, "order:create");
        assertThat(abandoned.isClaimed()).isTrue();
        assertThat(replacement.tryStart(requestId, "order:create").isClaimed()).isFalse();

        sleep(1_150L);
        AtomicInteger effects = new AtomicInteger();
        assertThat(replacement.executeIfNotDone(requestId, "order:create", "order:create:2", () -> {
            effects.incrementAndGet();
            return "recovered";
        })).isEqualTo("recovered");

        TaskLogService.TaskLog completed = replacement.get(requestId);
        assertThat(completed.getStatus()).isEqualTo(TaskLogService.TaskStatus.COMPLETED);
        assertThat(completed.getFencingToken()).isGreaterThan(abandoned.getFencingToken());
        assertThat(crashedWorker.renewLease(abandoned)).isFalse();
        assertThat(effects).hasValue(1);
    }

    @Test
    void staleWorkerCannotOverwriteRecoveredResult() throws Exception {
        TaskLogService staleWorker = service(1_000L);
        TaskLogService replacement = new TaskLogService(redis, new DistributedLock(redis), 1_000L);
        String requestId = "task-log-fencing";
        delete(requestId);
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseStaleAction = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var staleResult = executor.submit(() -> staleWorker.executeIfNotDone(
                    requestId, "order:create", "order:create:1", () -> {
                        actionStarted.countDown();
                        try {
                            releaseStaleAction.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return "stale";
                    }));
            assertThat(actionStarted.await(2, TimeUnit.SECONDS)).isTrue();

            sleep(1_150L);
            assertThat(replacement.executeIfNotDone(
                    requestId, "order:create", "order:create:1", () -> "recovered"))
                    .isEqualTo("recovered");

            releaseStaleAction.countDown();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> staleResult.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(TaskLogService.StaleTaskOwnerException.class);
        }

        assertThat(replacement.get(requestId).getResult()).isEqualTo("recovered");
    }

    private TaskLogService service(long leaseMs) {
        int port = Integer.parseInt(System.getenv("TASK_LOG_REDIS_PORT"));
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        return new TaskLogService(redis, new DistributedLock(redis), leaseMs);
    }

    private void delete(String requestId) {
        String base = "a2a:task:{" + requestId + "}";
        redis.delete(List.of("a2a:task:" + requestId, base + ":state", base + ":lease",
                base + ":fence", "a2a:lock:order:create:1",
                "a2a:lock:order:create:2"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
