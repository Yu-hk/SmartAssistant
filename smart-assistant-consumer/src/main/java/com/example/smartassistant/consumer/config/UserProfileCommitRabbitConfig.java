package com.example.smartassistant.consumer.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable RabbitMQ topology for post-response user-profile commits. */
@Configuration(proxyBeanMethods = false)
public class UserProfileCommitRabbitConfig {

    @Bean
    DirectExchange userProfileCommitExchange(
            @Value("${preference.commit.exchange}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    DirectExchange userProfileCommitDeadLetterExchange(
            @Value("${preference.commit.dead-letter-exchange}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    Queue userProfileCommitQueue(
            @Value("${preference.commit.queue}") String queue,
            @Value("${preference.commit.dead-letter-exchange}") String dlx,
            @Value("${preference.commit.dead-letter-routing-key}") String deadLetterRoutingKey) {
        return QueueBuilder.durable(queue)
                .deadLetterExchange(dlx)
                .deadLetterRoutingKey(deadLetterRoutingKey)
                .build();
    }

    @Bean
    Queue userProfileCommitDeadLetterQueue(
            @Value("${preference.commit.dead-letter-queue}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    Binding userProfileCommitBinding(
            Queue userProfileCommitQueue,
            DirectExchange userProfileCommitExchange,
            @Value("${preference.commit.routing-key}") String routingKey) {
        return BindingBuilder.bind(userProfileCommitQueue)
                .to(userProfileCommitExchange).with(routingKey);
    }

    @Bean
    Binding userProfileCommitDeadLetterBinding(
            Queue userProfileCommitDeadLetterQueue,
            DirectExchange userProfileCommitDeadLetterExchange,
            @Value("${preference.commit.dead-letter-routing-key}") String routingKey) {
        return BindingBuilder.bind(userProfileCommitDeadLetterQueue)
                .to(userProfileCommitDeadLetterExchange).with(routingKey);
    }
}
