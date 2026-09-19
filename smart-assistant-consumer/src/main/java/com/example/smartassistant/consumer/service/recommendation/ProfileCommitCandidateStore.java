package com.example.smartassistant.consumer.service.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.Optional;
import java.util.UUID;

/** Owned, generation-fenced staging for reference-only MQ messages; not a transactional outbox. */
@Service
public class ProfileCommitCandidateStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProfileGenerationFence fence;

    public ProfileCommitCandidateStore(JdbcTemplate jdbc, ObjectMapper json, ProfileGenerationFence fence) {
        this.jdbc=jdbc; this.json=json; this.fence=fence;
    }

    public String stage(UserProfileService.PreparedProfileCandidate candidate) {
        if (candidate == null || candidate.userId() == null || candidate.userId() <= 0
                || candidate.requestId() == null || candidate.requestId().isBlank()
                || candidate.requestId().length() > 128 || candidate.generation() < 0 || candidate.report() == null) {
            throw new IllegalArgumentException("Invalid profile candidate identity");
        }
        final String payload;
        try { payload=json.writeValueAsString(candidate); }
        catch (Exception error) { throw new IllegalArgumentException("Cannot encode profile candidate"); }
        return fence.write(candidate.userId(),candidate.generation(),()-> {
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO profile_commit_candidate (candidate_id,user_id,request_id,generation,payload,expires_at) "
                    + "VALUES (?,?,?,?,CAST(? AS jsonb),CURRENT_TIMESTAMP + INTERVAL '24 hours') "
                    + "ON CONFLICT (user_id,request_id,generation) DO NOTHING",
                    id,candidate.userId(),candidate.requestId(),candidate.generation(),payload);
            // First candidate wins; duplicate publication never refreshes content or retention.
            return jdbc.queryForObject("SELECT candidate_id FROM profile_commit_candidate WHERE user_id=? AND request_id=? AND generation=?",
                    String.class,candidate.userId(),candidate.requestId(),candidate.generation());
        });
    }

    public Optional<UserProfileService.PreparedProfileCandidate> resolve(UserProfileCommitRequestedEvent event) {
        fence.requireCurrent(event.userId(),event.generation());
        var rows=jdbc.query("SELECT payload::text FROM profile_commit_candidate WHERE candidate_id=? AND user_id=? "
                        + "AND request_id=? AND generation=? AND payload IS NOT NULL AND expires_at>CURRENT_TIMESTAMP",
                (rs,row)->rs.getString(1),event.candidateId(),event.userId(),event.requestId(),event.generation());
        if (rows.isEmpty()) return Optional.empty(); // completed, expired or deliberately erased
        try {
            var candidate=json.readValue(rows.getFirst(),UserProfileService.PreparedProfileCandidate.class);
            if (!event.userId().equals(candidate.userId()) || !event.requestId().equals(candidate.requestId())
                    || event.generation()!=candidate.generation()) throw new IllegalArgumentException();
            return Optional.of(candidate);
        } catch (Exception invalid) { throw new IllegalStateException("Invalid stored profile candidate"); }
    }

    public void retire(UserProfileCommitRequestedEvent event) {
        // Keep a content-free receipt for duplicate delivery until the fixed expiry.
        jdbc.update("UPDATE profile_commit_candidate SET payload=NULL,completed_at=CURRENT_TIMESTAMP "
                        + "WHERE candidate_id=? AND user_id=? AND request_id=? AND generation=? AND payload IS NOT NULL",
                event.candidateId(),event.userId(),event.requestId(),event.generation());
    }

    @Scheduled(fixedDelayString="${preference.commit.candidate-cleanup-ms:3600000}",initialDelayString="${preference.commit.candidate-cleanup-ms:3600000}")
    public void cleanupExpired() {
        // Bounded cleanup of this staging table only. Broker/DLQ references contain no payload.
        jdbc.update("DELETE FROM profile_commit_candidate WHERE candidate_id IN "
                + "(SELECT candidate_id FROM profile_commit_candidate WHERE expires_at<=CURRENT_TIMESTAMP ORDER BY expires_at LIMIT 500)");
    }
}
