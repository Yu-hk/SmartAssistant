package com.example.smartassistant.consumer.service.recommendation;

import java.time.Instant;

/** Durable command emitted only after a conversation turn completes successfully. */
public record UserProfileCommitRequestedEvent(
        String version,
        Long userId,
        String requestId,
        UserProfileService.PreparedProfileCandidate candidate,
        Instant requestedAt) {

    public static final String CURRENT_VERSION = "1";

    public static UserProfileCommitRequestedEvent of(
            UserProfileService.PreparedProfileCandidate candidate) {
        return new UserProfileCommitRequestedEvent(
                CURRENT_VERSION, candidate.userId(), candidate.requestId(),
                candidate, Instant.now());
    }
}
