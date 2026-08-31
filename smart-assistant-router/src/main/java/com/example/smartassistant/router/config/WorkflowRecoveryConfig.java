package com.example.smartassistant.router.config;

import com.example.smartassistant.router.service.checkpoint.LangGraphRedisCheckpointSaver;
import com.example.smartassistant.router.service.core.LangGraphRouteExecutionService;
import com.example.smartassistant.router.service.recovery.RabbitWorkflowRecoveryQueue;
import com.example.smartassistant.router.service.recovery.RedisWorkflowRecoveryQueue;
import com.example.smartassistant.router.service.recovery.WorkflowExecutionLeaseService;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryApplicationService;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryJobRepository;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryManager;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryNotificationPublisher;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryNotificationOutboxPublisher;
import com.example.smartassistant.router.service.recovery.RabbitWorkflowRecoveryNotificationPublisher;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Wires the durable transport used for automatic LangGraph workflow recovery. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "router.graph.recovery", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WorkflowRecoveryRabbitProperties.class)
public class WorkflowRecoveryConfig {

    @Bean
    @ConditionalOnProperty(prefix = "router.graph.recovery", name = "broker", havingValue = "redis")
    WorkflowRecoveryQueue redisWorkflowRecoveryQueue(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${router.graph.recovery.message-ttl-ms:7200000}") long messageTtlMs,
            @Value("${router.graph.recovery.dedup-ttl-ms:60000}") long dedupTtlMs) {
        return new RedisWorkflowRecoveryQueue(redisTemplate, objectMapper,
                Duration.ofMillis(messageTtlMs), Duration.ofMillis(dedupTtlMs));
    }

    @Bean
    @ConditionalOnProperty(prefix = "router.graph.recovery", name = "broker",
            havingValue = "rabbit", matchIfMissing = true)
    Declarables workflowRecoveryRabbitTopology(WorkflowRecoveryRabbitProperties properties,
                                                @Value("${router.graph.recovery.max-retries:3}")
                                                int maxRetries) {
        DirectExchange exchange = new DirectExchange(properties.getExchange(), true, false);
        DirectExchange deadLetterExchange = new DirectExchange(
                properties.getDeadLetterExchange(), true, false);
        DirectExchange notificationDeadLetterExchange = new DirectExchange(
                properties.getNotificationDeadLetterExchange(), true, false);
        Queue recoveryQueue = QueueBuilder.durable(properties.getQueue())
                .quorum()
                .deadLetterExchange(properties.getDeadLetterExchange())
                .deadLetterRoutingKey(properties.getDeadLetterRoutingKey())
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-overflow", "reject-publish")
                .withArgument("x-delivery-limit", Math.max(1, maxRetries + 1))
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(properties.getDeadLetterQueue())
                .quorum()
                .build();
        Queue notificationQueue = QueueBuilder.durable(properties.getNotificationQueue())
                .deadLetterExchange(properties.getNotificationDeadLetterExchange())
                .deadLetterRoutingKey(properties.getNotificationDeadLetterRoutingKey())
                .build();
        Queue notificationDeadLetterQueue = QueueBuilder
                .durable(properties.getNotificationDeadLetterQueue()).build();

        List<Declarable> declarations = new ArrayList<>();
        declarations.add(exchange);
        declarations.add(deadLetterExchange);
        declarations.add(notificationDeadLetterExchange);
        declarations.add(recoveryQueue);
        declarations.add(BindingBuilder.bind(recoveryQueue).to(exchange)
                .with(properties.getRoutingKey()));
        declarations.add(deadLetterQueue);
        declarations.add(BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange)
                .with(properties.getDeadLetterRoutingKey()));
        declarations.add(notificationQueue);
        declarations.add(BindingBuilder.bind(notificationQueue).to(exchange)
                .with(properties.getNotificationRoutingKey()));
        declarations.add(notificationDeadLetterQueue);
        declarations.add(BindingBuilder.bind(notificationDeadLetterQueue)
                .to(notificationDeadLetterExchange)
                .with(properties.getNotificationDeadLetterRoutingKey()));

        for (RabbitWorkflowRecoveryQueue.RetryRoute retry :
                RabbitWorkflowRecoveryQueue.buildRetryRoutes(properties)) {
            Queue retryQueue = QueueBuilder.durable(
                            RabbitWorkflowRecoveryQueue.retryQueueName(properties, retry.delayMs()))
                    .quorum()
                    .ttl(Math.toIntExact(Math.min(Integer.MAX_VALUE, retry.delayMs())))
                    .deadLetterExchange(properties.getExchange())
                    .deadLetterRoutingKey(properties.getRoutingKey())
                    .withArgument("x-dead-letter-strategy", "at-least-once")
                    .withArgument("x-overflow", "reject-publish")
                    .build();
            declarations.add(retryQueue);
            declarations.add(BindingBuilder.bind(retryQueue).to(exchange).with(retry.routingKey()));
        }
        return new Declarables(declarations);
    }

    @Bean
    @ConditionalOnProperty(prefix = "router.graph.recovery", name = "broker",
            havingValue = "rabbit", matchIfMissing = true)
    WorkflowRecoveryNotificationPublisher workflowRecoveryNotificationPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            WorkflowRecoveryRabbitProperties properties) {
        return new RabbitWorkflowRecoveryNotificationPublisher(
                rabbitTemplate, objectMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowRecoveryNotificationPublisher.class)
    WorkflowRecoveryNotificationPublisher noopWorkflowRecoveryNotificationPublisher() {
        return WorkflowRecoveryNotificationPublisher.noop();
    }

    @Bean
    WorkflowRecoveryNotificationOutboxPublisher workflowRecoveryNotificationOutboxPublisher(
            WorkflowRecoveryJobRepository jobRepository,
            WorkflowRecoveryNotificationPublisher notificationPublisher) {
        return new WorkflowRecoveryNotificationOutboxPublisher(
                jobRepository, notificationPublisher);
    }

    @Bean
    @ConditionalOnProperty(prefix = "router.graph.recovery", name = "broker",
            havingValue = "rabbit", matchIfMissing = true)
    WorkflowRecoveryQueue rabbitWorkflowRecoveryQueue(
            ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate,
            AmqpAdmin amqpAdmin,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            WorkflowRecoveryRabbitProperties properties,
            @Value("${router.graph.recovery.message-ttl-ms:7200000}") long messageTtlMs,
            @Value("${router.graph.recovery.dedup-ttl-ms:60000}") long dedupTtlMs) {
        rabbitTemplate.setMandatory(true);
        return new RabbitWorkflowRecoveryQueue(connectionFactory, rabbitTemplate, amqpAdmin,
                redisTemplate, objectMapper, properties,
                Duration.ofMillis(messageTtlMs), Duration.ofMillis(dedupTtlMs));
    }

    @Bean
    WorkflowRecoveryApplicationService workflowRecoveryApplicationService(
            LangGraphRedisCheckpointSaver checkpointSaver,
            WorkflowRecoveryQueue queue,
            WorkflowExecutionLeaseService leaseService,
            LangGraphRouteExecutionService graphExecutionService,
            WorkflowRecoveryJobRepository jobRepository,
            WorkflowRecoveryNotificationPublisher notificationPublisher) {
        return new WorkflowRecoveryApplicationService(checkpointSaver, queue, leaseService,
                graphExecutionService, jobRepository, notificationPublisher);
    }

    @Bean
    WorkflowRecoveryManager workflowRecoveryManager(
            LangGraphRedisCheckpointSaver checkpointSaver,
            WorkflowRecoveryQueue queue,
            WorkflowRecoveryApplicationService recoveryService,
            @Value("${router.graph.recovery.stale-after-ms:120000}") long staleAfterMs,
            @Value("${router.graph.recovery.visibility-timeout-ms:120000}") long visibilityTimeoutMs,
            @Value("${router.graph.recovery.scan-batch-size:100}") int scanBatchSize,
            @Value("${router.graph.recovery.max-retries:3}") int maxRetries,
            @Value("${router.graph.recovery.worker-count:2}") int workerCount,
            @Value("${router.graph.recovery.idle-poll-ms:250}") long idlePollMs) {
        return new WorkflowRecoveryManager(checkpointSaver, queue, recoveryService,
                staleAfterMs, Duration.ofMillis(visibilityTimeoutMs),
                scanBatchSize, maxRetries, workerCount, idlePollMs);
    }
}
