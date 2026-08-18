package com.example.smartassistant.router.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PostgreSQL repository. Publishing uses a row lock so one workflow has one active version. */
@Repository
public class JdbcWorkflowVersionRepository implements WorkflowVersionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public JdbcWorkflowVersionRepository(JdbcTemplate jdbcTemplate,
                                         TransactionTemplate transactionTemplate,
                                         ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public WorkflowVersion createDraft(String workflowKey, WorkflowDefinition definition, Long createdBy) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Integer nextVersion = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(version), 0) + 1 FROM workflow_versions WHERE workflow_key = ?",
                    Integer.class, workflowKey);
            try {
                jdbcTemplate.update("""
                        INSERT INTO workflow_versions
                            (workflow_key, version, status, definition, created_by)
                        VALUES (?, ?, 'DRAFT', CAST(? AS jsonb), ?)
                        """, workflowKey, nextVersion, writeDefinition(definition), createdBy);
                return find(workflowKey, nextVersion).orElseThrow();
            } catch (DuplicateKeyException ignored) {
                // Concurrent draft creation: recalculate the monotonic version.
            }
        }
        throw new IllegalStateException("could not allocate workflow version");
    }

    @Override
    public Optional<WorkflowVersion> find(String workflowKey, int version) {
        return jdbcTemplate.query("""
                        SELECT workflow_key, version, status, definition::text, checksum,
                               created_by, created_at, published_by, published_at
                        FROM workflow_versions WHERE workflow_key = ? AND version = ?
                        """, this::map, workflowKey, version).stream().findFirst();
    }

    @Override
    public Optional<WorkflowVersion> findPublished(String workflowKey) {
        return jdbcTemplate.query("""
                        SELECT workflow_key, version, status, definition::text, checksum,
                               created_by, created_at, published_by, published_at
                        FROM workflow_versions
                        WHERE workflow_key = ? AND status = 'PUBLISHED'
                        """, this::map, workflowKey).stream().findFirst();
    }

    @Override
    public List<WorkflowVersion> list(String workflowKey) {
        return jdbcTemplate.query("""
                SELECT workflow_key, version, status, definition::text, checksum,
                       created_by, created_at, published_by, published_at
                FROM workflow_versions WHERE workflow_key = ? ORDER BY version DESC
                """, this::map, workflowKey);
    }

    @Override
    public boolean publish(String workflowKey, int version, String checksum, Long publishedBy) {
        Boolean result = transactionTemplate.execute(status -> {
            List<VersionStatus> versions = jdbcTemplate.query(
                    "SELECT version, status FROM workflow_versions WHERE workflow_key = ? ORDER BY version FOR UPDATE",
                    (rs, rowNum) -> new VersionStatus(rs.getInt(1), rs.getString(2)), workflowKey);
            boolean draftExists = versions.stream().anyMatch(item -> item.version() == version
                    && "DRAFT".equals(item.status()));
            if (!draftExists) return false;
            jdbcTemplate.update("""
                    UPDATE workflow_versions SET status = 'ARCHIVED'
                    WHERE workflow_key = ? AND status = 'PUBLISHED'
                    """, workflowKey);
            int updated = jdbcTemplate.update("""
                    UPDATE workflow_versions
                    SET status = 'PUBLISHED', checksum = ?, published_by = ?, published_at = CURRENT_TIMESTAMP
                    WHERE workflow_key = ? AND version = ? AND status = 'DRAFT'
                    """, checksum, publishedBy, workflowKey, version);
            return updated == 1;
        });
        return Boolean.TRUE.equals(result);
    }

    private WorkflowVersion map(ResultSet rs, int rowNum) throws SQLException {
        try {
            Timestamp publishedAt = rs.getTimestamp("published_at");
            return new WorkflowVersion(
                    rs.getString("workflow_key"), rs.getInt("version"),
                    WorkflowVersion.Status.valueOf(rs.getString("status")),
                    objectMapper.readValue(rs.getString("definition"), WorkflowDefinition.class),
                    rs.getString("checksum"), nullableLong(rs, "created_by"),
                    rs.getTimestamp("created_at").toInstant(), nullableLong(rs, "published_by"),
                    publishedAt != null ? publishedAt.toInstant() : null);
        } catch (JsonProcessingException e) {
            throw new SQLException("invalid stored workflow definition", e);
        }
    }

    private String writeDefinition(WorkflowDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("workflow definition cannot be serialized", e);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record VersionStatus(int version, String status) {
    }
}
