package com.example.smartassistant.router.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRecoveryRabbitTopologyTest {

    @Test
    void declaresQuorumMainRetryAndDeadLetterQueues() {
        WorkflowRecoveryRabbitProperties properties = new WorkflowRecoveryRabbitProperties();
        properties.setRetryDelays(java.util.List.of(
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)));

        var topology = new WorkflowRecoveryConfig()
                .workflowRecoveryRabbitTopology(properties, 3)
                .getDeclarables();

        assertThat(topology).filteredOn(DirectExchange.class::isInstance).hasSize(3);
        assertThat(topology).filteredOn(Queue.class::isInstance).hasSize(7);
        assertThat(topology).filteredOn(Binding.class::isInstance).hasSize(7);
        assertThat(topology).filteredOn(Queue.class::isInstance)
                .map(Queue.class::cast)
                .extracting(Queue::getName)
                .contains(properties.getNotificationQueue());

        Queue main = topology.stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .filter(queue -> queue.getName().equals(properties.getQueue()))
                .findFirst()
                .orElseThrow();
        assertThat(main.getArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-dead-letter-exchange", properties.getDeadLetterExchange())
                .containsEntry("x-dead-letter-routing-key", properties.getDeadLetterRoutingKey())
                .containsEntry("x-dead-letter-strategy", "at-least-once")
                .containsEntry("x-overflow", "reject-publish")
                .containsEntry("x-delivery-limit", 4);
    }
}
