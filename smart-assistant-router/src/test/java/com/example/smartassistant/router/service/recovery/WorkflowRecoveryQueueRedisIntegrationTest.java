package com.example.smartassistant.router.service.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ROUTER_RECOVERY_REDIS_PORT", matches = "\\d+")
class WorkflowRecoveryQueueRedisIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private WorkflowRecoveryQueue queue;

    @BeforeEach
    void setUp() {
        int port = Integer.parseInt(System.getenv("ROUTER_RECOVERY_REDIS_PORT"));
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        redis.delete(redis.keys("a2a:workflow:{recovery}:*"));
        queue = new RedisWorkflowRecoveryQueue(redis, new ObjectMapper(),
                Duration.ofHours(1), Duration.ofMinutes(5));
    }

    @AfterEach
    void tearDown() {
        if (redis != null) redis.delete(redis.keys("a2a:workflow:{recovery}:*"));
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @Test
    void deduplicatesRetriesAndAcknowledgesOneCheckpointGeneration() {
        assertThat(queue.publish(command("recovery-1", "request-1", 100L))).isTrue();
        assertThat(queue.publish(command("recovery-2", "request-1", 100L))).isFalse();

        var first = queue.poll().orElseThrow();
        assertThat(first.attempts()).isZero();
        assertThat(queue.retry(first, "temporary", Duration.ZERO)).isTrue();

        var retried = queue.poll().orElseThrow();
        assertThat(retried.attempts()).isEqualTo(1);
        assertThat(retried.lastError()).isEqualTo("temporary");
        queue.acknowledge(retried);
        assertThat(queue.readySize()).isZero();
    }

    @Test
    void reclaimsAbandonedDeliveryAndStopsDeadLetterGenerationFromRepublishing() throws Exception {
        assertThat(queue.publish(command("recovery-3", "request-2", 200L))).isTrue();
        var abandoned = queue.poll().orElseThrow();
        Thread.sleep(20L);

        assertThat(queue.reclaimTimedOut(Duration.ofMillis(1), 3, 10)).isEqualTo(1);
        var reclaimed = queue.poll().orElseThrow();
        assertThat(reclaimed.attempts()).isEqualTo(1);
        queue.deadLetter(reclaimed, "exhausted");

        assertThat(queue.deadLetterSize()).isEqualTo(1L);
        assertThat(queue.publish(command("recovery-4", "request-2", 200L))).isFalse();
        assertThat(abandoned.recoveryId()).isEqualTo(reclaimed.recoveryId());
    }

    private static WorkflowRecoveryCommand command(String recoveryId, String requestId,
                                                   long checkpointVersion) {
        return new WorkflowRecoveryCommand(recoveryId, requestId, checkpointVersion,
                WorkflowRecoveryTrigger.AUTO_STALE, null, "test", "trace-test",
                0, System.currentTimeMillis(), null);
    }
}
