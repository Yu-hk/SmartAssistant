/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 */

package com.example.smartassistant.consumer.service.admin;

import com.example.smartassistant.consumer.infrastructure.db.DatabaseDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AdminServiceStatsTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DatabaseDialect dialect;

    private AdminService service;

    @BeforeEach
    void setUp() {
        service = new AdminService(jdbcTemplate, dialect);
        lenient().when(dialect.dateFunc(anyString()))
                .thenAnswer(invocation -> "DATE(" + invocation.getArgument(0) + ")");
        lenient().when(dialect.dateSub("7")).thenReturn("NOW() - INTERVAL '7 days'");
    }

    @Test
    void statsUseLatestTurnPerSessionAndMergeNormalizedIntents() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Number.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("COUNT(*) FROM (SELECT session_id, user_id")
                            && sql.contains("HAVING COUNT(*) = COUNT(total_tokens)")) return 1;
                    if (sql.contains("COUNT(*) FROM (SELECT session_id, user_id FROM routing_call_log")) return 2;
                    if (sql.contains("COUNT(DISTINCT user_id) FROM routing_call_log")) return 2;
                    if (sql.contains("COUNT(*) FROM routing_call_log")
                            && sql.contains("total_tokens IS NOT NULL")) return 3;
                    if (sql.equals("SELECT COUNT(*) FROM routing_call_log")) return 4;
                    if (sql.contains("SUM(total_tokens)")
                            && sql.contains("total_tokens IS NOT NULL")) return 300;
                    if (sql.contains("SUM(session_tokens)")) return 120;
                    if (sql.contains("COUNT(*) FROM (SELECT session_id, user_id FROM conversation_feedback")) return 1;
                    if (sql.contains("AVG(rating)")) return 4.0;
                    if (sql.contains("latest.status")) return 1;
                    if (sql.contains("latest.routed_agent")) return 1;
                    if (sql.contains("AVG(latency_ms)")) return 150.0;
                    return 0;
                });
        when(jdbcTemplate.queryForList(
                anyString(), eq(Long.class))).thenReturn(List.of(100L, 200L));
        when(jdbcTemplate.queryForList(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("status, COUNT(*) count")) {
                return List.of(
                        Map.of("status", "SUCCESS", "count", 1L),
                        Map.of("status", "HUMAN_TRANSFER", "count", 1L));
            }
            if (sql.contains("routed_agent, 'unknown'")) {
                return List.of(
                        Map.of("intent", "order", "count", 2L),
                        Map.of("intent", "order_service", "count", 3L));
            }
            if (sql.contains("session_day")) {
                return List.of(Map.of(
                        "session_day", Date.valueOf(LocalDate.now()),
                        "session_count", 2L,
                        "avg_satisfaction", 4.0));
            }
            return List.of();
        });

        AdminService.AdminStats stats = service.getStats();

        assertEquals(2, stats.totalSessions());
        assertEquals(300, stats.totalTokens());
        assertEquals(120.0, stats.avgTokensPerSession());
        assertEquals(1, stats.tokenTrackedSessions());
        assertEquals(3, stats.tokenTrackedTurns());
        assertEquals(4, stats.totalTurns());
        assertEquals(75.0, stats.tokenCoverageRate());
        assertEquals(50.0, stats.successRate());
        assertEquals(50.0, stats.handoffRate());
        assertEquals(150, stats.avgLatencyMs());
        assertEquals(200, stats.p95LatencyMs());
        assertEquals(1, stats.intentBreakdown().size());
        assertEquals("order", stats.intentBreakdown().getFirst().intent());
        assertEquals(5, stats.intentBreakdown().getFirst().count());
        assertEquals(7, stats.daily().size());
        assertEquals(2, stats.daily().getLast().sessionCount());
    }

    @Test
    void statsFailureIsVisibleInsteadOfReturningMisleadingZeros() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Number.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, service::getStats);
    }

    @Test
    void noTrackedTurnsKeepsTokenTotalsUnknown() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Number.class), any(Object[].class)))
                .thenReturn(0);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class))).thenReturn(List.of());

        AdminService.AdminStats stats = service.getStats();

        assertNull(stats.totalTokens());
        assertNull(stats.avgTokensPerSession());
        assertEquals(0, stats.tokenTrackedTurns());
        assertEquals(0.0, stats.tokenCoverageRate());
    }

    @Test
    void knowledgeBaseFailureIsVisibleInsteadOfReturningMisleadingEmptyState() {
        when(jdbcTemplate.queryForList(startsWith("SELECT id, category")))
                .thenThrow(new IllegalStateException("table unavailable"));

        assertThrows(IllegalStateException.class, service::getFaqs);
    }
}
