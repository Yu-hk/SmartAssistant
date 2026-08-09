/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.admin;

import com.example.smartassistant.common.db.DatabaseDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceSessionIsolationTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DatabaseDialect dialect;

    private AdminService adminService;

    @BeforeEach
    void setUp() {
        adminService = new AdminService(jdbcTemplate, dialect);
    }

    @Test
    void ordinarySessionListShouldBeScopedByAuthenticatedUser() {
        when(jdbcTemplate.queryForList(anyString(), eq(7L))).thenReturn(List.of());

        assertTrue(adminService.getSessions(7L, false).isEmpty());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), eq(7L));
        assertTrue(sql.getValue().contains("r.user_id = ?"));
        assertTrue(sql.getValue().contains("f.user_id = r.user_id"));
    }

    @Test
    void ordinarySessionDetailShouldBeScopedBySessionAndUser() {
        when(jdbcTemplate.queryForList(anyString(), eq("session-a"), eq(7L)))
                .thenReturn(List.of(Map.of("session_id", "session-a", "user_id", 7L)));

        assertTrue(adminService.getSessionDetail("session-a", 7L, false).isPresent());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), eq("session-a"), eq(7L));
        assertTrue(sql.getValue().contains("session_id = ? AND user_id = ?"));
    }

    @Test
    void ordinaryUserShouldNotSeeOrDeleteAnotherUsersSession() {
        when(jdbcTemplate.queryForList(anyString(), eq("session-b"), eq(7L)))
                .thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), eq("session-b"), eq(7L))).thenReturn(0);

        assertTrue(adminService.getSessionDetail("session-b", 7L, false).isEmpty());
        assertFalse(adminService.deleteSession("session-b", 7L, false));

        verify(jdbcTemplate, never()).update(
                contains("conversation_feedback"), anyString(), anyLong());
    }

    @Test
    void ordinaryDeleteShouldScopeBothLogsAndFeedbackByUser() {
        when(jdbcTemplate.update(
                contains("routing_call_log"), eq("session-a"), eq(7L))).thenReturn(2);
        when(jdbcTemplate.update(
                contains("conversation_feedback"), eq("session-a"), eq(7L))).thenReturn(1);

        assertTrue(adminService.deleteSession("session-a", 7L, false));

        verify(jdbcTemplate).update(
                contains("routing_call_log WHERE session_id = ? AND user_id = ?"),
                eq("session-a"), eq(7L));
        verify(jdbcTemplate).update(
                contains("conversation_feedback WHERE session_id = ? AND user_id = ?"),
                eq("session-a"), eq(7L));
    }

    @Test
    void administratorShouldRetainUnscopedListDetailAndDeleteAccess() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), eq("legacy-session")))
                .thenReturn(List.of(Map.of("session_id", "legacy-session")));
        when(jdbcTemplate.update(anyString(), eq("legacy-session"))).thenReturn(1);

        assertTrue(adminService.getSessions(1L, true).isEmpty());
        assertTrue(adminService.getSessionDetail("legacy-session", 1L, true).isPresent());
        assertTrue(adminService.deleteSession("legacy-session", 1L, true));

        ArgumentCaptor<String> listSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(listSql.capture());
        assertFalse(listSql.getValue().contains("r.user_id = ?"));
        verify(jdbcTemplate).queryForList(
                eq("SELECT * FROM routing_call_log WHERE session_id = ? ORDER BY created_at"),
                eq("legacy-session"));
        verify(jdbcTemplate).update(
                eq("DELETE FROM routing_call_log WHERE session_id = ?"), eq("legacy-session"));
        verify(jdbcTemplate).update(
                eq("DELETE FROM conversation_feedback WHERE session_id = ?"), eq("legacy-session"));
    }
}
