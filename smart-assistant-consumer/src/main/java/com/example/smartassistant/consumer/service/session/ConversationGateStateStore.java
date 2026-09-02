package com.example.smartassistant.consumer.service.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Durable lifecycle mirror and database-level exclusivity backstop. */
@Service
public class ConversationGateStateStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationGateStateStore.class);
    private final JdbcTemplate jdbcTemplate;

    public ConversationGateStateStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(ConversationGateService.GateDecision decision) {
        Long userId = numericUserId(decision.userId());
        if (userId == null) return;
        switch (decision.status()) {
            case ACQUIRED, REATTACHED -> activate(userId, decision.sessionId(), "ACTIVE_RUNNING");
            case SESSION_SUSPENDED -> upsert(userId, decision.sessionId(), "SUSPENDED");
            case REQUEST_BLOCKED -> activate(userId, decision.sessionId(), "ACTIVE_RUNNING");
            case UNAVAILABLE -> { }
        }
    }

    public void requestCompleted(String userId, String sessionId) {
        Long numericUserId = numericUserId(userId);
        if (numericUserId == null) return;
        jdbcTemplate.update("UPDATE conversation_session_state " +
                        "SET status = 'ACTIVE_IDLE', closed_at = NULL, updated_at = CURRENT_TIMESTAMP " +
                        "WHERE user_id = ? AND session_id = ? AND status <> 'CLOSED'",
                numericUserId, sessionId);
    }

    public void closed(String userId, String sessionId) {
        Long numericUserId = numericUserId(userId);
        if (numericUserId == null) return;
        jdbcTemplate.update("UPDATE conversation_session_state " +
                        "SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP " +
                        "WHERE user_id = ? AND session_id = ?",
                numericUserId, sessionId);
    }

    public boolean isSuspended(String userId, String sessionId) {
        Long numericUserId = numericUserId(userId);
        if (numericUserId == null) return false;
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM conversation_session_state WHERE user_id = ? AND session_id = ?",
                String.class, numericUserId, sessionId);
        return statuses.stream().anyMatch(status ->
                "SUSPENDED".equalsIgnoreCase(status) || "FROZEN".equalsIgnoreCase(status));
    }

    @Transactional
    public void resumed(String userId, String sessionId) {
        Long numericUserId = numericUserId(userId);
        if (numericUserId == null) {
            throw new IllegalArgumentException("userId must be numeric");
        }
        activate(numericUserId, sessionId, "ACTIVE_IDLE");
    }

    private void activate(Long userId, String sessionId, String status) {
        // Short transaction-free statements are intentional: never retain a DB lock during model execution.
        jdbcTemplate.update("UPDATE conversation_session_state " +
                        "SET status = 'SUSPENDED', updated_at = CURRENT_TIMESTAMP " +
                        "WHERE user_id = ? AND session_id <> ? " +
                        "AND status IN ('ACTIVE', 'ACTIVE_IDLE', 'ACTIVE_RUNNING')",
                userId, sessionId);
        upsert(userId, sessionId, status);
    }

    private void upsert(Long userId, String sessionId, String status) {
        int updated = jdbcTemplate.update("UPDATE conversation_session_state " +
                        "SET status = ?, closed_at = NULL, updated_at = CURRENT_TIMESTAMP " +
                        "WHERE user_id = ? AND session_id = ?",
                status, userId, sessionId);
        if (updated > 0) return;
        try {
            jdbcTemplate.update("INSERT INTO conversation_session_state " +
                            "(user_id, session_id, status, closed_at, updated_at) " +
                            "VALUES (?, ?, ?, NULL, CURRENT_TIMESTAMP)",
                    userId, sessionId, status);
        } catch (DuplicateKeyException race) {
            jdbcTemplate.update("UPDATE conversation_session_state " +
                            "SET status = ?, closed_at = NULL, updated_at = CURRENT_TIMESTAMP " +
                            "WHERE user_id = ? AND session_id = ?",
                    status, userId, sessionId);
        } catch (RuntimeException error) {
            log.warn("[ConversationGateState] insert failed: userId={}, sessionId={}, error={}",
                    userId, sessionId, error.getMessage());
            throw error;
        }
    }

    private static Long numericUserId(String userId) {
        try {
            return userId == null ? null : Long.valueOf(userId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
