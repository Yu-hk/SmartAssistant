package com.example.smartassistant.consumer.service.notification;

import com.example.smartassistant.common.recovery.WorkflowRecoveryCompletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserNotificationStoreTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private UserNotificationStore store;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("""
                CREATE TABLE routing_call_log (
                    id BIGINT PRIMARY KEY, session_id VARCHAR(100), request_id VARCHAR(128),
                    user_id BIGINT, response_summary CLOB, status VARCHAR(20), error_message CLOB)
                """);
        jdbc.execute("""
                CREATE TABLE user_notifications (
                    id VARCHAR(64) PRIMARY KEY, event_id VARCHAR(64) NOT NULL UNIQUE,
                    user_id BIGINT NOT NULL, type VARCHAR(64) NOT NULL,
                    title VARCHAR(200) NOT NULL, content CLOB, session_id VARCHAR(100),
                    request_id VARCHAR(128), status VARCHAR(20) NOT NULL,
                    created_at TIMESTAMP NOT NULL, read_at TIMESTAMP)
                """);
        jdbc.update("INSERT INTO routing_call_log "
                        + "(id, session_id, request_id, user_id, status, error_message) "
                        + "VALUES (1, 'session-1', 'request-1', 7, 'FAILED', 'crashed')");
        store = new UserNotificationStore(jdbc);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void storesOnceAndClosesTheFailedConversationTurn() {
        WorkflowRecoveryCompletedEvent event = new WorkflowRecoveryCompletedEvent(
                "recovery-1", "request-1", 7L, "SUCCEEDED", "恢复结果",
                Instant.parse("2026-08-27T08:00:00Z"));

        UserNotificationStore.StoredNotification first = store.storeRecovery(event);
        UserNotificationStore.StoredNotification duplicate = store.storeRecovery(event);

        assertThat(first.created()).isTrue();
        assertThat(first.notification().sessionId()).isEqualTo("session-1");
        assertThat(duplicate.created()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT response_summary FROM routing_call_log WHERE id = 1", String.class))
                .isEqualTo("恢复结果");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM routing_call_log WHERE id = 1", String.class))
                .isEqualTo("SUCCESS");
        assertThat(store.unread(7L, 10)).hasSize(1);
        assertThat(store.markRead(first.notification().id(), 7L)).isTrue();
        assertThat(store.unreadCount(7L)).isZero();
    }
}
