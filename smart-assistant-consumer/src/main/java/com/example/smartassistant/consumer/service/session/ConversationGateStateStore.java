package com.example.smartassistant.consumer.service.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Durable lifecycle mirror for independent conversations. */
@Service
public class ConversationGateStateStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationGateStateStore.class);
    private final JdbcTemplate jdbcTemplate;

    public ConversationGateStateStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Persist an empty conversation before its first request enters the queue. */
    public void create(String userId, String sessionId) {
        Long numericUserId = numericUserId(userId);
        if (numericUserId == null) throw new IllegalArgumentException("userId must be numeric");
        try {
            jdbcTemplate.update("INSERT INTO conversation_session_state " +
                            "(user_id, session_id, status, closed_at, updated_at) " +
                            "VALUES (?, ?, 'CREATED', NULL, CURRENT_TIMESTAMP)",
                    numericUserId, sessionId);
        } catch (DuplicateKeyException existing) {
            // Retrying session creation must never reopen a closed or suspended conversation.
            if (isClosed(userId, sessionId)) {
                throw new IllegalStateException("Session is closed");
            }
        }
    }

    public boolean isClosed(String userId, String sessionId) {
        Long numericUserId = numericUserId(userId);
        if (numericUserId == null) return false;
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM conversation_session_state WHERE user_id = ? AND session_id = ?",
                String.class, numericUserId, sessionId);
        return statuses.stream().anyMatch("CLOSED"::equalsIgnoreCase);
    }

    public boolean isOpen(String userId, String sessionId) {
        Long numericUserId = numericUserId(userId);
        if (numericUserId == null) return false;
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM conversation_session_state WHERE user_id = ? AND session_id = ?",
                String.class, numericUserId, sessionId);
        return statuses.stream().anyMatch(status ->
                !"CLOSED".equalsIgnoreCase(status)
                        && !"SUSPENDED".equalsIgnoreCase(status)
                        && !"FROZEN".equalsIgnoreCase(status));
    }

    public void record(ConversationGateService.GateDecision decision) {
        Long userId = numericUserId(decision.userId());
        if (userId == null) return;
        switch (decision.status()) {
            case ACQUIRED, REATTACHED -> activate(userId, decision.sessionId(), "ACTIVE_RUNNING");
            case SESSION_SUSPENDED -> upsert(userId, decision.sessionId(), "SUSPENDED");
            case SESSION_CLOSED -> { }
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

    /** Null means a live/known owner; missing records alone are not enough without Redis grace/running checks. */
    public String staleOwnerReason(String userId, String sessionId) {
        Long id = numericUserId(userId);
        if (id == null) return null;
        List<String> states = jdbcTemplate.queryForList(
                "SELECT status FROM conversation_session_state WHERE user_id = ? AND session_id = ?",
                String.class, id, sessionId);
        if (states.stream().anyMatch("CLOSED"::equalsIgnoreCase)) return "CLOSED";
        if (!states.isEmpty()) return null;
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routing_call_log WHERE user_id = ? AND session_id = ?", Long.class, id, sessionId);
        return count != null && count == 0 ? "MISSING" : null;
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
        // Activation changes only this conversation; other sessions remain independent.
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
