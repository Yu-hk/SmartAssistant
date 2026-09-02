package com.example.smartassistant.consumer.service.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** Applies a prepared profile only after the successful-turn commit event arrives. */
@Service
public class UserProfileCommitListener {

    private final ObjectMapper objectMapper;
    private final UserProfileService userProfileService;

    public UserProfileCommitListener(
            ObjectMapper objectMapper, UserProfileService userProfileService) {
        this.objectMapper = objectMapper;
        this.userProfileService = userProfileService;
    }

    @RabbitListener(queues = "${preference.commit.queue}")
    public void receive(Message message) throws IOException {
        UserProfileCommitRequestedEvent event = objectMapper.readValue(
                message.getBody(), UserProfileCommitRequestedEvent.class);
        if (!UserProfileCommitRequestedEvent.CURRENT_VERSION.equals(event.version())
                || event.userId() == null
                || event.requestId() == null || event.requestId().isBlank()
                || event.candidate() == null
                || !event.userId().equals(event.candidate().userId())
                || !event.requestId().equals(event.candidate().requestId())) {
            throw new IllegalArgumentException("Invalid user-profile commit event");
        }
        userProfileService.commitPreparedProfile(event.candidate());
    }
}
