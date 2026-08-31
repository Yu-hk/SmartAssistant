package com.example.smartassistant.router.service.recovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** PostgreSQL audit store for recovery requests and their terminal outcomes. */
@Repository
public class JdbcWorkflowRecoveryJobRepository implements WorkflowRecoveryJobRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcWorkflowRecoveryJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void create(WorkflowRecoveryJob job) {
        jdbcTemplate.update("""
                INSERT INTO workflow_recovery_jobs
                    (recovery_id, request_id, checkpoint_updated_at_epoch_ms, trigger,
                     workflow_owner_id, requested_by, reason, status, attempts, last_error,
                     requested_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, job.recoveryId(), job.requestId(), job.checkpointUpdatedAtEpochMs(),
                job.trigger().name(), job.workflowOwnerId(), job.requestedBy(), job.reason(),
                job.status().name(), job.attempts(), job.lastError(),
                Timestamp.from(job.requestedAt()), Timestamp.from(job.updatedAt()));
    }

    @Override
    public Optional<WorkflowRecoveryJob> findById(String recoveryId) {
        return jdbcTemplate.query("""
                SELECT recovery_id, request_id, checkpoint_updated_at_epoch_ms, trigger,
                       workflow_owner_id, requested_by, reason, status, attempts, last_error,
                       result, requested_at, updated_at
                FROM workflow_recovery_jobs WHERE recovery_id = ?
                """, this::map, recoveryId).stream().findFirst();
    }

    @Override
    public Optional<WorkflowRecoveryJob> findLatest(String requestId,
                                                     long checkpointUpdatedAtEpochMs,
                                                     WorkflowRecoveryTrigger trigger) {
        return jdbcTemplate.query("""
                SELECT recovery_id, request_id, checkpoint_updated_at_epoch_ms, trigger,
                       workflow_owner_id, requested_by, reason, status, attempts, last_error,
                       result, requested_at, updated_at
                FROM workflow_recovery_jobs
                WHERE request_id = ? AND checkpoint_updated_at_epoch_ms = ? AND trigger = ?
                ORDER BY requested_at DESC LIMIT 1
                """, this::map, requestId, checkpointUpdatedAtEpochMs, trigger.name())
                .stream().findFirst();
    }

    @Override
    public void update(String recoveryId, WorkflowRecoveryStatus status,
                       int attempts, String lastError) {
        jdbcTemplate.update("""
                UPDATE workflow_recovery_jobs
                SET status = ?, attempts = ?, last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE recovery_id = ?
                """, status.name(), attempts, lastError, recoveryId);
    }

    @Override
    public void complete(String recoveryId, int attempts, String result) {
        jdbcTemplate.update("""
                UPDATE workflow_recovery_jobs
                SET status = ?, attempts = ?, last_error = NULL, result = ?,
                    notification_pending = TRUE, notification_published_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE recovery_id = ?
                """, WorkflowRecoveryStatus.SUCCEEDED.name(), attempts, result, recoveryId);
    }

    @Override
    public List<WorkflowRecoveryJob> findPendingNotifications(int limit) {
        return jdbcTemplate.query("""
                SELECT recovery_id, request_id, checkpoint_updated_at_epoch_ms, trigger,
                       workflow_owner_id, requested_by, reason, status, attempts, last_error,
                       result, requested_at, updated_at
                FROM workflow_recovery_jobs
                WHERE status = 'SUCCEEDED' AND notification_pending = TRUE
                ORDER BY updated_at ASC LIMIT ?
                """, this::map, Math.max(1, Math.min(limit, 100)));
    }

    @Override
    public void markNotificationPublished(String recoveryId) {
        jdbcTemplate.update("""
                UPDATE workflow_recovery_jobs
                SET notification_pending = FALSE,
                    notification_published_at = CURRENT_TIMESTAMP
                WHERE recovery_id = ? AND notification_pending = TRUE
                """, recoveryId);
    }

    private WorkflowRecoveryJob map(ResultSet rs, int rowNum) throws SQLException {
        return new WorkflowRecoveryJob(
                rs.getString("recovery_id"), rs.getString("request_id"),
                rs.getLong("checkpoint_updated_at_epoch_ms"),
                WorkflowRecoveryTrigger.valueOf(rs.getString("trigger")),
                nullableLong(rs, "workflow_owner_id"), nullableLong(rs, "requested_by"),
                rs.getString("reason"), WorkflowRecoveryStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"), rs.getString("last_error"), rs.getString("result"),
                rs.getTimestamp("requested_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
