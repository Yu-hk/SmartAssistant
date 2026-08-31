package com.example.smartassistant.router.config;

import com.example.smartassistant.router.service.recovery.RabbitWorkflowRecoveryQueue;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryCommand;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryQueue;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryTrigger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ROUTER_RECOVERY_RABBIT_PORT", matches = "\\d+")
@EnabledIfEnvironmentVariable(named = "ROUTER_RECOVERY_REDIS_PORT", matches = "\\d+")
class RabbitWorkflowRecoveryQueueIntegrationTest {

    private CachingConnectionFactory rabbitConnectionFactory;
    private LettuceConnectionFactory redisConnectionFactory;
    private StringRedisTemplate redis;
    private RabbitAdmin rabbitAdmin;
    private RabbitTemplate rabbitTemplate;
    private WorkflowRecoveryRabbitProperties properties;
    private RabbitWorkflowRecoveryQueue queue;
    private String requestSuffix;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        requestSuffix = suffix;
        properties = new WorkflowRecoveryRabbitProperties();
        properties.setExchange("test.workflow." + suffix);
        properties.setQueue("test.workflow.recovery." + suffix);
        properties.setRoutingKey("test.workflow.recovery");
        properties.setDeadLetterExchange("test.workflow.dlx." + suffix);
        properties.setDeadLetterQueue("test.workflow.recovery.dlq." + suffix);
        properties.setDeadLetterRoutingKey("test.workflow.recovery.dead");
        properties.setRetryDelays(java.util.List.of(Duration.ofSeconds(1)));

        rabbitConnectionFactory = new CachingConnectionFactory("127.0.0.1",
                Integer.parseInt(System.getenv("ROUTER_RECOVERY_RABBIT_PORT")));
        rabbitConnectionFactory.setUsername(System.getenv().getOrDefault(
                "ROUTER_RECOVERY_RABBIT_USERNAME", "smartassistant"));
        rabbitConnectionFactory.setPassword(System.getenv().getOrDefault(
                "ROUTER_RECOVERY_RABBIT_PASSWORD", "rabbit123"));
        rabbitConnectionFactory.setPublisherConfirmType(
                CachingConnectionFactory.ConfirmType.CORRELATED);
        rabbitConnectionFactory.setPublisherReturns(true);
        rabbitTemplate = new RabbitTemplate(rabbitConnectionFactory);
        rabbitTemplate.setMandatory(true);
        rabbitAdmin = new RabbitAdmin(rabbitConnectionFactory);
        declareTopology();

        redisConnectionFactory = new LettuceConnectionFactory("127.0.0.1",
                Integer.parseInt(System.getenv("ROUTER_RECOVERY_REDIS_PORT")));
        redisConnectionFactory.afterPropertiesSet();
        redisConnectionFactory.start();
        redis = new StringRedisTemplate(redisConnectionFactory);
        redis.afterPropertiesSet();

        queue = newQueue();
    }

    @AfterEach
    void tearDown() {
        if (queue != null) queue.destroy();
        if (rabbitAdmin != null && properties != null) {
            rabbitAdmin.deleteQueue(properties.getQueue());
            rabbitAdmin.deleteQueue(properties.getDeadLetterQueue());
            for (var retry : RabbitWorkflowRecoveryQueue.buildRetryRoutes(properties)) {
                rabbitAdmin.deleteQueue(RabbitWorkflowRecoveryQueue.retryQueueName(
                        properties, retry.delayMs()));
            }
            rabbitAdmin.deleteExchange(properties.getExchange());
            rabbitAdmin.deleteExchange(properties.getDeadLetterExchange());
        }
        if (rabbitConnectionFactory != null) rabbitConnectionFactory.destroy();
        if (redisConnectionFactory != null) redisConnectionFactory.destroy();
    }

    @Test
    void confirmsRetriesDeadLettersAndRequeuesAnUnacknowledgedCrashDelivery() throws Exception {
        String retryRequest = "request-retry-" + requestSuffix;
        String deadRequest = "request-dead-" + requestSuffix;
        String crashRequest = "request-crash-" + requestSuffix;
        assertThat(queue.publish(command("recovery-retry-1", retryRequest, 100L))).isTrue();
        assertThat(queue.publish(command("recovery-retry-2", retryRequest, 100L))).isFalse();

        var first = awaitDelivery(queue);
        assertThat(queue.retry(first, "temporary", Duration.ofSeconds(1))).isTrue();
        assertThat(queue.poll()).isEmpty();

        var retried = awaitDelivery(queue);
        assertThat(retried.attempts()).isEqualTo(1);
        assertThat(retried.lastError()).isEqualTo("temporary");
        queue.acknowledge(retried);

        assertThat(queue.publish(command("recovery-dead-1", deadRequest, 200L))).isTrue();
        var failed = awaitDelivery(queue);
        queue.deadLetter(failed, "exhausted");
        assertThat(awaitDeadLetterSize()).isEqualTo(1L);
        assertThat(queue.publish(command("recovery-dead-2", deadRequest, 200L))).isFalse();

        assertThat(queue.publish(command("recovery-crash-1", crashRequest, 300L))).isTrue();
        var abandoned = awaitDelivery(queue);
        queue.destroy();
        queue = newQueue();
        var redelivered = awaitDelivery(queue);
        assertThat(redelivered.recoveryId()).isEqualTo(abandoned.recoveryId());
        queue.acknowledge(redelivered);
    }

    private RabbitWorkflowRecoveryQueue newQueue() {
        return new RabbitWorkflowRecoveryQueue(rabbitConnectionFactory, rabbitTemplate,
                rabbitAdmin, redis, new ObjectMapper(), properties,
                Duration.ofHours(1), Duration.ofMinutes(5));
    }

    private void declareTopology() {
        var declarables = new WorkflowRecoveryConfig()
                .workflowRecoveryRabbitTopology(properties, 3)
                .getDeclarables();
        for (var declarable : declarables) {
            if (declarable instanceof Exchange exchange) rabbitAdmin.declareExchange(exchange);
            if (declarable instanceof Queue declaredQueue) rabbitAdmin.declareQueue(declaredQueue);
            if (declarable instanceof Binding binding) rabbitAdmin.declareBinding(binding);
        }
    }

    private WorkflowRecoveryCommand awaitDelivery(WorkflowRecoveryQueue target)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        Optional<WorkflowRecoveryCommand> delivery;
        do {
            delivery = target.poll();
            if (delivery.isPresent()) return delivery.orElseThrow();
            Thread.sleep(50L);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("RabbitMQ recovery delivery did not arrive within five seconds");
    }

    private static WorkflowRecoveryCommand command(String recoveryId, String requestId,
                                                   long checkpointVersion) {
        return new WorkflowRecoveryCommand(recoveryId, requestId, checkpointVersion,
                WorkflowRecoveryTrigger.AUTO_STALE, null, "test", "trace-test",
                0, System.currentTimeMillis(), null);
    }

    private long awaitDeadLetterSize() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        long size;
        do {
            size = queue.deadLetterSize();
            if (size > 0) return size;
            Thread.sleep(50L);
        } while (System.currentTimeMillis() < deadline);
        return 0L;
    }
}
