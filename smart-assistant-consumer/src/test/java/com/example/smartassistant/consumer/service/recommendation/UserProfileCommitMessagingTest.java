package com.example.smartassistant.consumer.service.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

class UserProfileCommitMessagingTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void publisherSendsPersistentRequestIdentifiedMessage() throws Exception {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        UserProfileCommitPublisher publisher = new UserProfileCommitPublisher(
                rabbitTemplate, objectMapper, "profile.exchange", "profile.commit", 1_000L);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(
                org.mockito.ArgumentMatchers.eq("profile.exchange"),
                org.mockito.ArgumentMatchers.eq("profile.commit"),
                org.mockito.ArgumentMatchers.any(Message.class),
                org.mockito.ArgumentMatchers.any(CorrelationData.class));

        UserProfileService.PreparedProfileCandidate candidate = candidate("request-42");
        publisher.publish(candidate);

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(
                org.mockito.ArgumentMatchers.eq("profile.exchange"),
                org.mockito.ArgumentMatchers.eq("profile.commit"), message.capture(),
                org.mockito.ArgumentMatchers.any(CorrelationData.class));
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getValue().getMessageProperties().getMessageId())
                .isEqualTo("request-42");
        UserProfileCommitRequestedEvent event = objectMapper.readValue(
                message.getValue().getBody(), UserProfileCommitRequestedEvent.class);
        assertThat(event.userId()).isEqualTo(42L);
        assertThat(event.requestId()).isEqualTo("request-42");
        assertThat(event.candidate()).isEqualTo(candidate);
    }

    @Test
    void listenerDelegatesValidCommitEvent() throws Exception {
        UserProfileService service = mock(UserProfileService.class);
        UserProfileCommitListener listener = new UserProfileCommitListener(objectMapper, service);
        UserProfileService.PreparedProfileCandidate candidate = candidate("request-valid");
        byte[] body = objectMapper.writeValueAsBytes(new UserProfileCommitRequestedEvent(
                UserProfileCommitRequestedEvent.CURRENT_VERSION,
                42L, "request-valid", candidate, Instant.now()));

        listener.receive(MessageBuilder.withBody(body).build());

        verify(service).commitPreparedProfile(candidate);
    }

    @Test
    void listenerRejectsUnsupportedEventVersion() throws Exception {
        UserProfileCommitListener listener = new UserProfileCommitListener(
                objectMapper, mock(UserProfileService.class));
        byte[] body = objectMapper.writeValueAsBytes(new UserProfileCommitRequestedEvent(
                "99", 42L, "request-invalid", candidate("request-invalid"), Instant.now()));

        assertThatThrownBy(() -> listener.receive(MessageBuilder.withBody(body).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid user-profile commit event");
    }

    private static UserProfileService.PreparedProfileCandidate candidate(String requestId) {
        return new UserProfileService.PreparedProfileCandidate(
                42L, requestId, 0L,
                LLMPreferenceExtractor.UserInsightReport.empty("信息不足"),
                "推荐耳机", null, List.of());
    }
}
