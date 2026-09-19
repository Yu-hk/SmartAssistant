package com.example.smartassistant.consumer.service.recommendation;

import java.time.Instant;

/** Durable command emitted only after a conversation turn completes successfully. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record UserProfileCommitRequestedEvent(
        String version,
        Long userId,
        String requestId,
        UserProfileService.PreparedProfileCandidate candidate,
        Instant requestedAt,
        String candidateId,
        Long generation) {

    public static final String CURRENT_VERSION = "2";
    public static final String LEGACY_VERSION = "1";

    public UserProfileCommitRequestedEvent(String version, Long userId, String requestId,
            UserProfileService.PreparedProfileCandidate candidate, Instant requestedAt) {
        this(version,userId,requestId,candidate,requestedAt,null,null);
    }

    public static UserProfileCommitRequestedEvent of(
            UserProfileService.PreparedProfileCandidate candidate) {
        return new UserProfileCommitRequestedEvent(
                LEGACY_VERSION, candidate.userId(), candidate.requestId(),
                candidate, Instant.now());
    }

    public static UserProfileCommitRequestedEvent reference(UserProfileService.PreparedProfileCandidate candidate, String candidateId) {
        return new UserProfileCommitRequestedEvent(CURRENT_VERSION,candidate.userId(),candidate.requestId(),
                null,Instant.now(),candidateId,candidate.generation());
    }
}
