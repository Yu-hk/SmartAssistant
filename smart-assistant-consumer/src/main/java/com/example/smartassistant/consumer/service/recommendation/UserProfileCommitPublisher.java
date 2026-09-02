package com.example.smartassistant.consumer.service.recommendation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Publishes a persistent, request-idempotent profile commit command. */
@Service
public class UserProfileCommitPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String exchange;
    private final String routingKey;
    private final long confirmTimeoutMs;

    public UserProfileCommitPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${preference.commit.exchange}") String exchange,
            @Value("${preference.commit.routing-key}") String routingKey,
            @Value("${preference.commit.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.confirmTimeoutMs = Math.max(100L, confirmTimeoutMs);
    }

    public void publish(UserProfileService.PreparedProfileCandidate candidate) {
        if (candidate == null || candidate.userId() == null
                || candidate.requestId() == null || candidate.requestId().isBlank()) return;
        Long userId = candidate.userId();
        String requestId = candidate.requestId();
        UserProfileCommitRequestedEvent event =
                UserProfileCommitRequestedEvent.of(candidate);
        try {
            Message message = MessageBuilder.withBody(objectMapper.writeValueAsBytes(event))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding(StandardCharsets.UTF_8.name())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setMessageId(requestId)
                    .setHeader("profile-user-id", userId)
                    .setHeader("profile-request-id", requestId)
                    .build();
            CorrelationData correlation = new CorrelationData(
                    requestId + ":profile:" + UUID.randomUUID());
            rabbitTemplate.send(exchange, routingKey, message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw new IllegalStateException(
                        "RabbitMQ rejected user-profile commit: " + confirm.reason());
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ returned user-profile commit: "
                        + correlation.getReturned().getReplyText());
            }
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Unable to serialize user-profile commit", error);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("User-profile commit publish interrupted", interrupted);
        } catch (Exception error) {
            if (error instanceof IllegalStateException illegalState) throw illegalState;
            throw new IllegalStateException("User-profile commit publish was not confirmed", error);
        }
    }
}
