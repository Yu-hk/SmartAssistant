package com.example.smartassistant.consumer.service.notification;

import com.example.smartassistant.common.recovery.WorkflowRecoveryCompletedEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persists recovery events idempotently and closes the original conversation turn. */
@Service
public class UserNotificationStore {

    private static final String RECOVERY_TYPE = "WORKFLOW_RECOVERY";
    private final JdbcTemplate jdbcTemplate;

    public UserNotificationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public StoredNotification storeRecovery(WorkflowRecoveryCompletedEvent event) {
        Optional<UserNotification> existing = findByEventId(event.recoveryId());
        if (existing.isPresent()) return new StoredNotification(existing.orElseThrow(), false);

        String result = normalizeResult(event.result());
        String sessionId = findSessionId(event.requestId(), event.userId()).orElse(null);
        Instant createdAt = event.completedAt() == null ? Instant.now() : event.completedAt();
        UserNotification notification = new UserNotification(
                UUID.randomUUID().toString(), RECOVERY_TYPE, "回答已恢复", result,
                sessionId, event.requestId(), "UNREAD", createdAt);

        if (result != null) {
            jdbcTemplate.update(
                    "UPDATE routing_call_log SET response_summary = ?, status = 'SUCCESS', "
                            + "error_message = NULL WHERE request_id = ? AND user_id = ?",
                    result, event.requestId(), event.userId());
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO user_notifications "
                            + "(id, event_id, user_id, type, title, content, session_id, request_id, "
                            + "status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'UNREAD', ?)",
                    notification.id(), event.recoveryId(), event.userId(), notification.type(),
                    notification.title(), notification.content(), notification.sessionId(),
                    notification.requestId(), Timestamp.from(createdAt));
            return new StoredNotification(notification, true);
        } catch (DuplicateKeyException duplicate) {
            return new StoredNotification(findByEventId(event.recoveryId()).orElseThrow(), false);
        }
    }

    public List<UserNotification> unread(Long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query(
                "SELECT id, type, title, content, session_id, request_id, status, created_at "
                        + "FROM user_notifications WHERE user_id = ? AND status = 'UNREAD' "
                        + "ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> map(rs.getString("id"), rs.getString("type"),
                        rs.getString("title"), rs.getString("content"),
                        rs.getString("session_id"), rs.getString("request_id"),
                        rs.getString("status"), rs.getTimestamp("created_at")),
                userId, safeLimit);
    }

    public long unreadCount(Long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_notifications WHERE user_id = ? AND status = 'UNREAD'",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    public boolean markRead(String notificationId, Long userId) {
        return jdbcTemplate.update(
                "UPDATE user_notifications SET status = 'READ', read_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND user_id = ? AND status = 'UNREAD'",
                notificationId, userId) > 0;
    }

    private Optional<String> findSessionId(String requestId, Long userId) {
        List<String> sessions = jdbcTemplate.queryForList(
                "SELECT session_id FROM routing_call_log WHERE request_id = ? AND user_id = ? "
                        + "AND session_id IS NOT NULL ORDER BY id DESC LIMIT 1",
                String.class, requestId, userId);
        return sessions.stream().findFirst();
    }

    private Optional<UserNotification> findByEventId(String eventId) {
        List<UserNotification> rows = jdbcTemplate.query(
                "SELECT id, type, title, content, session_id, request_id, status, created_at "
                        + "FROM user_notifications WHERE event_id = ?",
                (rs, rowNum) -> map(rs.getString("id"), rs.getString("type"),
                        rs.getString("title"), rs.getString("content"),
                        rs.getString("session_id"), rs.getString("request_id"),
                        rs.getString("status"), rs.getTimestamp("created_at")), eventId);
        return rows.stream().findFirst();
    }

    private static UserNotification map(String id, String type, String title, String content,
                                        String sessionId, String requestId, String status,
                                        Timestamp createdAt) {
        return new UserNotification(id, type, title, content, sessionId, requestId, status,
                createdAt == null ? Instant.now() : createdAt.toInstant());
    }

    private static String normalizeResult(String result) {
        if (result == null || result.isBlank()) {
            return "工作流已恢复完成，但没有返回可展示的结果。";
        }
        String normalized = result.strip();
        return normalized.length() <= 20_000 ? normalized : normalized.substring(0, 20_000);
    }

    public record StoredNotification(UserNotification notification, boolean created) {
    }
}
