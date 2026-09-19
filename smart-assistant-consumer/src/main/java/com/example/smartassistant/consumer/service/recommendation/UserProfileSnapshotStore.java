package com.example.smartassistant.consumer.service.recommendation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL source of truth for current profile snapshots and immutable change events. */
@Service
public class UserProfileSnapshotStore {

    public static final String SCHEMA_VERSION = "ecommerce-profile-v1";
    public static final String PROMPT_VERSION = "2.1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ProfileGenerationFence generationFence;

    @Value("${spring.ai.deepseek.chat.options.model:unknown}")
    private String modelName = "unknown";

    public UserProfileSnapshotStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                    ProfileGenerationFence generationFence) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.generationFence = generationFence;
    }

    public long captureGeneration(Long userId) { return generationFence.capture(userId); }
    public void requireGeneration(Long userId, long generation) { generationFence.requireCurrent(userId, generation); }

    public Optional<Snapshot> load(Long userId) {
        if (userId == null) return Optional.empty();
        generationFence.requireRecoverySafe();
        List<Snapshot> rows = jdbcTemplate.query(
                "SELECT s.user_id, s.profile_version, s.schema_version, s.report::text, "
                        + "s.source_max_message_id, s.created_at, s.updated_at "
                        + "FROM user_profile_snapshot s LEFT JOIN profile_lifecycle l ON l.user_id = s.user_id "
                        + "WHERE s.user_id = ? AND COALESCE(l.analysis_enabled, true) "
                        + "AND s.generation = COALESCE(l.generation, 0)",
                (rs, rowNum) -> new Snapshot(
                        rs.getLong("user_id"), rs.getLong("profile_version"),
                        rs.getString("schema_version"), rs.getString("report"),
                        nullableLong(rs, "source_max_message_id"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("updated_at"))),
                userId);
        return rows.stream().findFirst();
    }

    public boolean isRequestApplied(Long userId, String requestId) {
        if (userId == null || requestId == null || requestId.isBlank()) return false;
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_profile_change_log "
                        + "WHERE user_id = ? AND request_id = ?)",
                Boolean.class, userId, requestId);
        return Boolean.TRUE.equals(applied);
    }

    public Snapshot save(Long userId, String requestId, long expectedVersion,
                         LLMPreferenceExtractor.UserInsightReport report,
                         Long sourceMaxMessageId, List<Long> evidenceMessageIds) {
        // Compatibility path never upgrades a legacy caller to the current generation.
        return save(userId, requestId, expectedVersion, report, sourceMaxMessageId, evidenceMessageIds, 0L);
    }

    public Snapshot save(Long userId, String requestId, long expectedVersion,
                         LLMPreferenceExtractor.UserInsightReport report,
                         Long sourceMaxMessageId, List<Long> evidenceMessageIds, long generation) {
        if (userId == null || report == null) {
            throw new IllegalArgumentException("userId and report are required");
        }
        return generationFence.write(userId, generation, () -> persist(userId, requestId, expectedVersion,
                report, sourceMaxMessageId, evidenceMessageIds, generation));
    }

    private Snapshot persist(Long userId, String requestId, long expectedVersion,
                             LLMPreferenceExtractor.UserInsightReport report,
                             Long sourceMaxMessageId, List<Long> evidenceMessageIds, long generation) {
        if (isRequestApplied(userId, requestId)) {
            return load(userId).orElseThrow(() -> new IllegalStateException(
                    "Applied user-profile request has no snapshot: " + requestId));
        }
        Optional<Snapshot> current = load(userId);
        if ("KEEP".equals(report.profileUpdate().action())) {
            return current.orElseGet(() -> {
                Instant now = Instant.now();
                return new Snapshot(userId, 0L, SCHEMA_VERSION, writeJson(report),
                        sourceMaxMessageId, now, now);
            });
        }

        String reportJson = writeJson(report);
        String action = current.isEmpty() ? "CREATE" : "UPDATE";
        long baseVersion = current.map(Snapshot::profileVersion).orElse(0L);
        if (baseVersion != expectedVersion) {
            throw new OptimisticProfileUpdateException(userId, expectedVersion, baseVersion);
        }
        long newVersion = baseVersion + 1L;
        LLMPreferenceExtractor.CommerceAssessment assessment = report.commerceAssessment();
        int changed;
        if (current.isEmpty()) {
            changed = jdbcTemplate.update(
                    "INSERT INTO user_profile_snapshot "
                            + "(user_id, profile_version, schema_version, report, reliable, "
                            + "purchase_stage, purchase_intent_score, churn_risk, "
                            + "source_max_message_id, generation, created_at, updated_at) "
                            + "VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
                            + "ON CONFLICT (user_id) DO NOTHING",
                    userId, newVersion, SCHEMA_VERSION, reportJson, assessment.reliable(),
                    assessment.purchaseStage(), assessment.purchaseIntentScore(),
                    assessment.churnRisk(), sourceMaxMessageId, generation);
        } else {
            changed = jdbcTemplate.update(
                    "UPDATE user_profile_snapshot SET profile_version = ?, schema_version = ?, "
                            + "report = CAST(? AS jsonb), reliable = ?, purchase_stage = ?, "
                            + "purchase_intent_score = ?, churn_risk = ?, source_max_message_id = ?, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE user_id = ? AND profile_version = ?",
                    newVersion, SCHEMA_VERSION, reportJson, assessment.reliable(),
                    assessment.purchaseStage(), assessment.purchaseIntentScore(),
                    assessment.churnRisk(), sourceMaxMessageId, userId, baseVersion);
        }
        if (changed != 1) {
            long actualVersion = load(userId).map(Snapshot::profileVersion).orElse(0L);
            throw new OptimisticProfileUpdateException(userId, expectedVersion, actualVersion);
        }

        String beforeHash = current.map(Snapshot::reportJson).map(UserProfileSnapshotStore::sha256)
                .orElse(null);
        String afterHash = sha256(reportJson);
        jdbcTemplate.update(
                "INSERT INTO user_profile_change_log "
                        + "(event_id, user_id, request_id, base_version, new_version, action, "
                        + "changed_fields, removed_fields, reason, evidence_refs, model_name, "
                        + "prompt_version, before_hash, after_hash, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, "
                        + "CAST(? AS jsonb), ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), userId, requestId, baseVersion, newVersion, action,
                writeJson(report.profileUpdate().changedFields()),
                writeJson(report.profileUpdate().removedFields()), report.profileUpdate().reason(),
                writeJson(evidenceMessageIds == null ? List.of() : evidenceMessageIds),
                modelName, PROMPT_VERSION, beforeHash, afterHash);

        Instant now = Instant.now();
        return new Snapshot(userId, newVersion, SCHEMA_VERSION, reportJson,
                sourceMaxMessageId, current.map(Snapshot::createdAt).orElse(now), now);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize user profile", error);
        }
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash user profile", error);
        }
    }

    public record Snapshot(Long userId, long profileVersion, String schemaVersion,
                           String reportJson, Long sourceMaxMessageId,
                           Instant createdAt, Instant updatedAt) {
    }

    public static class OptimisticProfileUpdateException extends RuntimeException {
        public OptimisticProfileUpdateException(Long userId, long expected, long actual) {
            super("User profile version conflict: userId=" + userId
                    + ", expected=" + expected + ", actual=" + actual);
        }
    }
}
