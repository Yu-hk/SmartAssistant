/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.common.db.DatabaseDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据分析面板 API。
 *
 * <p>使用 {@link DatabaseDialect} 屏蔽 PG/MySQL 语法差异。</p>
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseDialect dialect;

    public AnalyticsController(JdbcTemplate jdbcTemplate, DatabaseDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        try {
            Map<String, Object> stats = new HashMap<>();

            Long totalUsers = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT user_id) FROM conversation_feedback", Long.class);
            stats.put("totalUsers", totalUsers != null ? totalUsers : 0L);

            Long totalConversations = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM conversation_feedback", Long.class);
            stats.put("totalConversations", totalConversations != null ? totalConversations : 0L);

            Double avgCTR = jdbcTemplate.queryForObject(
                    "SELECT AVG(ctr) FROM (SELECT " +
                    dialect.countFilter("clicked = " + dialect.trueLiteral()) +
                    " as ctr FROM routing_call_log " +
                    "WHERE created_at >= " + dialect.dateSub("7") + " ) subquery",
                    Double.class);
            stats.put("avgCTR", avgCTR != null ? Math.round(avgCTR * 10.0) / 10.0 : 0.0);

            String topIntent;
            try {
                topIntent = jdbcTemplate.queryForObject(
                        "SELECT routed_agent FROM routing_call_log " +
                        "WHERE created_at >= " + dialect.dateSub("7") + " " +
                        "GROUP BY routed_agent ORDER BY COUNT(*) DESC " + dialect.limit(1),
                        String.class);
            } catch (Exception e) {
                topIntent = "GENERAL";
            }
            stats.put("topIntent", topIntent != null ? topIntent : "GENERAL");

            log.info("[Analytics] stats: users={}, conversations={}, ctr={}",
                    stats.get("totalUsers"), stats.get("totalConversations"), stats.get("avgCTR"));
            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("[Analytics] 获取统计数据失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "totalUsers", 0, "totalConversations", 0,
                    "avgCTR", 0.0, "topIntent", "N/A"));
        }
    }

    @GetMapping("/top-suggestions")
    public ResponseEntity<List<Map<String, Object>>> getTopSuggestions() {
        try {
            return ResponseEntity.ok(jdbcTemplate.queryForList(
                    "SELECT suggestion_text as text, COUNT(*) as clicks " +
                    "FROM routing_call_log " +
                    "WHERE suggestion_text IS NOT NULL AND clicked = " + dialect.trueLiteral() + " " +
                    "AND created_at >= " + dialect.dateSub("30") + " " +
                    "GROUP BY suggestion_text ORDER BY clicks DESC " + dialect.limit(5)));
        } catch (Exception e) {
            log.warn("[Analytics] top-suggestions 失败: {}", e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/ab-test")
    public ResponseEntity<Map<String, Object>> getABTestData() {
        try {
            Map<String, Object> result = new HashMap<>();
            String baseSQL = "SELECT suggestion_position as position, AVG( " +
                    dialect.countFilter("clicked = " + dialect.trueLiteral()) +
                    " ) as ctr FROM routing_call_log " +
                    "WHERE ab_test_group = '%s' " +
                    "AND created_at >= " + dialect.dateSub("7") + " " +
                    "GROUP BY suggestion_position ORDER BY suggestion_position";

            result.put("control", jdbcTemplate.queryForList(String.format(baseSQL, "control")));
            result.put("variant", jdbcTemplate.queryForList(String.format(baseSQL, "variant")));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("[Analytics] ab-test 失败: {}", e.getMessage());
            return ResponseEntity.ok(Map.of());
        }
    }

    @GetMapping("/preference-distribution")
    public ResponseEntity<List<Map<String, Object>>> getPreferenceDistribution() {
        try {
            return ResponseEntity.ok(jdbcTemplate.queryForList(
                    "SELECT routed_agent as intent, COUNT(*) as count, " +
                    "ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2) as percentage " +
                    "FROM routing_call_log " +
                    "WHERE created_at >= " + dialect.dateSub("30") + " " +
                    "GROUP BY routed_agent ORDER BY count DESC"));
        } catch (Exception e) {
            log.warn("[Analytics] preference-distribution 失败: {}", e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/click-trend")
    public ResponseEntity<List<Map<String, Object>>> getClickTrend() {
        try {
            return ResponseEntity.ok(jdbcTemplate.queryForList(
                    "SELECT " + dialect.dateFunc("created_at") + " as date, " +
                    dialect.countFilter("clicked = " + dialect.trueLiteral()) + " as clicks " +
                    "FROM routing_call_log " +
                    "WHERE created_at >= " + dialect.dateSub("7") + " " +
                    "GROUP BY " + dialect.dateFunc("created_at") + " ORDER BY date"));
        } catch (Exception e) {
            log.warn("[Analytics] click-trend 失败: {}", e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }
}
