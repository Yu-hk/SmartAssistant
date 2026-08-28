package com.example.smartassistant.consumer.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the durable recovery-completion topology from the consuming side. */
@Configuration(proxyBeanMethods = false)
public class WorkflowRecoveryNotificationRabbitConfig {

    @Bean
    DirectExchange workflowRecoveryNotificationExchange(
            @Value("${workflow.recovery.notifications.exchange}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    DirectExchange workflowRecoveryNotificationDeadLetterExchange(
            @Value("${workflow.recovery.notifications.dead-letter-exchange}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    Queue workflowRecoveryNotificationQueue(
            @Value("${workflow.recovery.notifications.queue}") String queue,
            @Value("${workflow.recovery.notifications.dead-letter-exchange}") String dlx,
            @Value("${workflow.recovery.notifications.dead-letter-routing-key}") String dlqKey) {
        return QueueBuilder.durable(queue)
                .deadLetterExchange(dlx).deadLetterRoutingKey(dlqKey).build();
    }


    @Bean
    Queue workflowRecoveryNotificationDeadLetterQueue(
            @Value("${workflow.recovery.notifications.dead-letter-queue}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    Binding workflowRecoveryNotificationBinding(
            Queue workflowRecoveryNotificationQueue,
            DirectExchange workflowRecoveryNotificationExchange,
            @Value("${workflow.recovery.notifications.routing-key}") String routingKey) {
        return BindingBuilder.bind(workflowRecoveryNotificationQueue)
                .to(workflowRecoveryNotificationExchange).with(routingKey);
    }

    @Bean
    Binding workflowRecoveryNotificationDeadLetterBinding(
            Queue workflowRecoveryNotificationDeadLetterQueue,
            DirectExchange workflowRecoveryNotificationDeadLetterExchange,
            @Value("${workflow.recovery.notifications.dead-letter-routing-key}") String routingKey) {
        return BindingBuilder.bind(workflowRecoveryNotificationDeadLetterQueue)
                .to(workflowRecoveryNotificationDeadLetterExchange).with(routingKey);
    }
}
