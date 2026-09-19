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
    private final ProfileCommitCandidateStore candidates;

    public UserProfileCommitListener(
            ObjectMapper objectMapper, UserProfileService userProfileService, ProfileCommitCandidateStore candidates) {
        this.objectMapper = objectMapper;
        this.userProfileService = userProfileService;
        this.candidates = candidates;
    }

    @RabbitListener(queues = "${preference.commit.queue}")
    public void receive(Message message) throws IOException {
        UserProfileCommitRequestedEvent event = objectMapper.readValue(
                message.getBody(), UserProfileCommitRequestedEvent.class);
        if (event.userId() == null || event.userId() <= 0
                || event.requestId() == null || event.requestId().isBlank() || event.requestId().length()>128) {
            throw new IllegalArgumentException("Invalid user-profile commit event");
        }
        if (UserProfileCommitRequestedEvent.CURRENT_VERSION.equals(event.version())) {
            if (event.candidate()!=null || event.generation()==null || event.generation()<0 || event.candidateId()==null
                    || !event.candidateId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                throw new IllegalArgumentException("Invalid user-profile commit event");
            }
            try { candidates.resolve(event).ifPresent(userProfileService::commitPreparedProfile); }
            catch (ProfileGenerationFence.Rejected obsolete) { /* ACK stale work without analysis. */ }
            candidates.retire(event);
            return;
        }
        if (!UserProfileCommitRequestedEvent.LEGACY_VERSION.equals(event.version())
                || event.candidateId()!=null || event.generation()!=null
                || event.candidate() == null
                || !event.userId().equals(event.candidate().userId())
                || !event.requestId().equals(event.candidate().requestId())) {
            throw new IllegalArgumentException("Invalid user-profile commit event");
        }
        userProfileService.commitPreparedProfile(event.candidate());
    }
}
