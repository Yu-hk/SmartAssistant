package com.example.smartassistant.router.service.recovery;

import com.example.smartassistant.common.recovery.WorkflowRecoveryCompletedEvent;
import com.example.smartassistant.router.config.WorkflowRecoveryRabbitProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** RabbitMQ publisher for user-facing workflow recovery notifications. */
public class RabbitWorkflowRecoveryNotificationPublisher
        implements WorkflowRecoveryNotificationPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final WorkflowRecoveryRabbitProperties properties;

    public RabbitWorkflowRecoveryNotificationPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            WorkflowRecoveryRabbitProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(WorkflowRecoveryCompletedEvent event) {
        try {
            Message message = MessageBuilder
                    .withBody(objectMapper.writeValueAsBytes(event))
                    .setContentType("application/json")
                    .setContentEncoding(StandardCharsets.UTF_8.name())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setMessageId(event.recoveryId())
                    .build();
            CorrelationData correlation = new CorrelationData(
                    event.recoveryId() + ":notification:" + UUID.randomUUID());
            rabbitTemplate.send(properties.getExchange(),
                    properties.getNotificationRoutingKey(), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    properties.getConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw new IllegalStateException(
                        "RabbitMQ rejected recovery notification: " + confirm.reason());
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ returned recovery notification: "
                        + correlation.getReturned().getReplyText());
            }
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Recovery notification serialization failed", error);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Recovery notification publish interrupted", interrupted);
        } catch (Exception error) {
            if (error instanceof IllegalStateException illegalState) throw illegalState;
            throw new IllegalStateException("Recovery notification publish was not confirmed", error);
        }
    }
}
