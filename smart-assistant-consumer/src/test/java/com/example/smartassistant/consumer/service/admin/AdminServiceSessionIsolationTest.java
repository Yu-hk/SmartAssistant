/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 */

package com.example.smartassistant.consumer.service.admin;

import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.common.audit.ToolUsageHeaders;
import com.example.smartassistant.common.db.DatabaseDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void ordinarySessionListIsScopedEvenWhenCallerHasAdminRole() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Number.class), any(Object[].class)))
                .thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        assertTrue(adminService.getSessions(7L, true).isEmpty());

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(
                countSql.capture(), eq(Number.class), any(Object[].class));
        assertTrue(countSql.getValue().contains("r.user_id = ?"));
    }

    @Test
    void adminSessionSearchReturnsStablePagedContract() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Number.class), any(Object[].class)))
                .thenReturn(3);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("session_id", "session-a");
        row.put("user_id", 7L);
        row.put("username", "alice");
        row.put("title", "Beijing weather");
        row.put("agent_name", "weather_service");
        row.put("status", "SUCCESS");
        row.put("satisfaction", 5);
        row.put("satisfaction_comment", "helpful");
        row.put("message_count", 4L);
        row.put("total_tokens", 450L);
        row.put("token_tracked_turns", 2L);
        row.put("total_turns", 2L);
        row.put("created_at", "2026-08-09T10:00:00");
        row.put("updated_at", "2026-08-09T10:01:00");
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row));

        AdminService.SessionPage result = adminService.searchAdminSessions(
                "weather", 7L, "SUCCESS", "weather", 1, 20);

        assertEquals(3, result.total());
        assertEquals(1, result.page());
        assertEquals(20, result.size());
        assertEquals("session-a", result.items().getFirst().sessionId());
        assertEquals("SUCCESS", result.items().getFirst().status());
        assertEquals(450L, result.items().getFirst().totalTokens());
        assertTrue(result.items().getFirst().tokenUsageComplete());

        ArgumentCaptor<String> dataSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(dataSql.capture(), any(Object[].class));
        assertTrue(dataSql.getValue().contains("LIMIT ? OFFSET ?"));
        assertTrue(dataSql.getValue().contains("HUMAN_TRANSFER"));
    }

    @Test
    void sessionDetailExposesPerTurnUsageWithoutPresentingPartialSumAsTotal() {
        Map<String, Object> tracked = new LinkedHashMap<>();
        tracked.put("id", 1L);
        tracked.put("request_id", "request-1");
        tracked.put("user_input", "first question");
        tracked.put("response_summary", "first answer");
        tracked.put("routed_agent", "general_service");
        tracked.put("status", "SUCCESS");
        tracked.put("latency_ms", 100L);
        tracked.put("prompt_tokens", 100L);
        tracked.put("completion_tokens", 20L);
        tracked.put("total_tokens", 120L);
        tracked.put("llm_received_question", "effective prompt");
        tracked.put("tool_calls", ToolUsageHeaders.encode(new ToolUsageCache.ToolUsage(
                true, List.of(new ToolUsageCache.ToolCall("queryWeather", "SUCCESS", 15)))));
        tracked.put("created_at", "2026-08-09T10:00:00");

        Map<String, Object> historical = new LinkedHashMap<>();
        historical.put("id", 2L);
        historical.put("request_id", null);
        historical.put("user_input", "historical question");
        historical.put("response_summary", "historical answer");
        historical.put("routed_agent", "general_service");
        historical.put("status", "SUCCESS");
        historical.put("latency_ms", 80L);
        historical.put("prompt_tokens", null);
        historical.put("completion_tokens", null);
        historical.put("total_tokens", null);
        historical.put("created_at", "2026-08-09T10:01:00");

        when(jdbcTemplate.queryForList(
                startsWith("SELECT id, request_id"), eq("session-a"), eq(7L)))
                .thenReturn(List.of(tracked, historical));
        when(jdbcTemplate.queryForList(
                startsWith("SELECT username"), eq(String.class), eq(7L)))
                .thenReturn(List.of("alice"));
        when(jdbcTemplate.queryForList(
                startsWith("SELECT rating"), eq("session-a"), eq(7L)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(
                startsWith("SELECT status FROM conversation_session_state"),
                eq(String.class), eq("session-a"), eq(7L)))
                .thenReturn(List.of("ACTIVE"));

        AdminService.SessionDetail detail = adminService
                .getAdminSessionDetail("session-a", 7L).orElseThrow();

        assertEquals(1, detail.tokenTrackedTurns());
        assertEquals(2, detail.totalTurns());
        assertFalse(detail.tokenUsageComplete());
        assertNull(detail.totalTokens());
        assertEquals(120L, detail.messages().get(1).totalTokens());
        assertEquals("request-1", detail.messages().get(1).requestId());
        assertEquals("effective prompt", detail.messages().get(1).promptSnapshot());
        assertTrue(detail.messages().get(1).toolUsageComplete());
        assertEquals("queryWeather", detail.messages().get(1).toolCalls().getFirst().name());
        assertNull(detail.messages().get(3).totalTokens());
        assertNull(detail.messages().get(3).requestId());
        assertNull(detail.messages().get(3).toolUsageComplete());
    }

    @Test
    void satisfactionIsOwnedAndUpdatesInsteadOfCreatingDuplicates() {
        when(jdbcTemplate.queryForList(
                startsWith("SELECT id FROM routing_call_log"), eq("session-a"), eq(7L)))
                .thenReturn(List.of(Map.of("id", 1L)));
        when(jdbcTemplate.update(
                startsWith("UPDATE conversation_feedback"), any(Object[].class)))
                .thenReturn(0, 1);
        when(jdbcTemplate.queryForList(
                startsWith("SELECT routed_agent"), eq(String.class), eq("session-a"), eq(7L)))
                .thenReturn(List.of("weather_service"));
        when(jdbcTemplate.update(
                startsWith("INSERT INTO conversation_feedback"), any(Object[].class)))
                .thenReturn(1);

        assertTrue(adminService.saveSatisfaction(
                "session-a", 7L, 5, "first").isPresent());
        assertTrue(adminService.saveSatisfaction(
                "session-a", 7L, 4, "updated").isPresent());

        verify(jdbcTemplate, times(1)).update(
                startsWith("INSERT INTO conversation_feedback"), any(Object[].class));
        verify(jdbcTemplate, times(2)).update(
                startsWith("UPDATE conversation_feedback"), any(Object[].class));
    }

    @Test
    void satisfactionCannotBeWrittenForAnotherUsersSession() {
        when(jdbcTemplate.queryForList(
                startsWith("SELECT id FROM routing_call_log"), eq("session-b"), eq(7L)))
                .thenReturn(List.of());

        assertTrue(adminService.saveSatisfaction(
                "session-b", 7L, 5, "spoofed").isEmpty());

        verify(jdbcTemplate, never()).update(
                contains("conversation_feedback"), any(Object[].class));
    }

    @Test
    void closingSessionUsesLifecycleTableWithoutChangingCallStatus() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Number.class), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(
                startsWith("UPDATE conversation_session_state"), any(Object[].class)))
                .thenReturn(0);
        when(jdbcTemplate.update(
                startsWith("INSERT INTO conversation_session_state"), any(Object[].class)))
                .thenReturn(1);

        assertTrue(adminService.closeSession("session-a", 7L));

        verify(jdbcTemplate, never()).update(
                contains("UPDATE routing_call_log"), any(Object[].class));
        verify(jdbcTemplate).update(
                startsWith("INSERT INTO conversation_session_state"), any(Object[].class));
    }

    @Test
    void ambiguousAdminSessionIdFailsClosedInsteadOfCrossUserAccess() {
        when(jdbcTemplate.queryForList(
                contains("GROUP BY user_id"), eq("shared-session")))
                .thenReturn(List.of(Map.of("user_id", 7L), Map.of("user_id", 8L)));

        assertTrue(adminService.getAdminSessionDetail("shared-session", null).isEmpty());
        assertFalse(adminService.deleteAdminSession("shared-session", null));

        verify(jdbcTemplate, never()).update(
                startsWith("DELETE FROM routing_call_log"), any(Object[].class));
    }

    @Test
    void exactAdminDeleteUsesUserAndSessionCompoundKey() {
        when(jdbcTemplate.update(
                contains("routing_call_log WHERE session_id = ? AND user_id = ?"),
                eq("shared-session"), eq(7L))).thenReturn(2);

        assertTrue(adminService.deleteAdminSession("shared-session", 7L));

        verify(jdbcTemplate).update(
                contains("conversation_feedback WHERE session_id = ? AND user_id = ?"),
                eq("shared-session"), eq(7L));
        verify(jdbcTemplate).update(
                contains("conversation_session_state WHERE session_id = ? AND user_id = ?"),
                eq("shared-session"), eq(7L));
    }
}
