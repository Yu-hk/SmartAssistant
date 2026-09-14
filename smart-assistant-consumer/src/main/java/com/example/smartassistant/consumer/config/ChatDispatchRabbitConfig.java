package com.example.smartassistant.consumer.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RabbitMQ 4.0–4.2 quorum fair-share priority, matching the deployed 4.1 broker. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatDispatchProperties.class)
public class ChatDispatchRabbitConfig {
    public static final String EXCHANGE = "smart.chat.dispatch.v1";
    public static final String QUEUE = "smart.chat.dispatch.v1.quorum";
    public static final String ROUTING_KEY = "route";
    public static final String DLX = "smart.chat.dispatch.v1.dlx";
    public static final String DLQ = "smart.chat.dispatch.v1.dead";

    @Bean
    Declarables chatDispatchTopology(ChatDispatchProperties properties) {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var deadExchange = new DirectExchange(DLX, true, false);
        var queue = QueueBuilder.durable(QUEUE).quorum()
                .ttl((int) properties.queueWaitMs()).maxLength(properties.maxLength())
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .deadLetterExchange(DLX).deadLetterRoutingKey(ROUTING_KEY)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-delivery-limit", 3).build();
        var deadQueue = QueueBuilder.durable(DLQ).quorum().ttl(7 * 24 * 60 * 60 * 1000).build();
        return new Declarables(exchange, deadExchange, queue, deadQueue,
                BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY),
                BindingBuilder.bind(deadQueue).to(deadExchange).with(ROUTING_KEY));
    }

    @Bean
    SimpleRabbitListenerContainerFactory chatDispatchContainerFactory(
            ConnectionFactory connectionFactory, ChatDispatchProperties properties) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrentConsumers(properties.concurrency());
        factory.setMaxConcurrentConsumers(properties.concurrency());
        factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        // Deliberately no Spring retry advice around a business execution.
        return factory;
    }
}
