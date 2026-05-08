package com.example.smartassistant.consumer.controller;

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
 * 数据分析面板 API
 * 提供智能建议系统的统计数据和可视化指标
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    
    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private final JdbcTemplate jdbcTemplate;
    
    public AnalyticsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * 获取分析面板统计数据
     * 
     * @return 包含用户数、对话数、点击率等统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        try {
            Map<String, Object> stats = new HashMap<>();
            
            // 1. 活跃用户数（从 conversation_feedback 表获取）
            Long totalUsersObj = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM conversation_feedback", 
                Long.class
            );
            stats.put("totalUsers", totalUsersObj != null ? totalUsersObj : 0L);
            
            // 2. 反馈总数
            Long totalConversationsObj = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_feedback", 
                Long.class
            );
            stats.put("totalConversations", totalConversationsObj != null ? totalConversationsObj : 0L);
            
            // 3. 平均点击率（从 routing_call_log 计算）
            Double avgCTR = jdbcTemplate.queryForObject(
                """
                SELECT AVG(click_through_rate)
                FROM (
                    SELECT
                        CASE WHEN clicked = true THEN 100.0 ELSE 0.0 END as click_through_rate
                    FROM routing_call_logs
                    WHERE created_at >= NOW() - INTERVAL '7 days'
                ) subquery
                """,
                Double.class
            );
            stats.put("avgCTR", avgCTR != null ? Math.round(avgCTR * 10.0) / 10.0 : 0.0);
            
            // 4. 热门意图（最近7天）
            String topIntent = jdbcTemplate.queryForObject(
                """
                SELECT routed_agent
                FROM routing_call_logs
                WHERE created_at >= NOW() - INTERVAL '7 days'
                GROUP BY routed_agent
                ORDER BY COUNT(*) DESC
                LIMIT 1
                """,
                String.class
            );
            stats.put("topIntent", topIntent != null ? topIntent : "GENERAL");
            
            log.info("[Analytics] 统计数据查询成功: users={}, conversations={}, ctr={}", 
                stats.get("totalUsers"), stats.get("totalConversations"), stats.get("avgCTR"));
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            log.error("[Analytics] 获取统计数据失败: {}", e.getMessage(), e);
            // 返回默认值，避免前端报错
            Map<String, Object> defaultStats = Map.of(
                "totalUsers", 0,
                "totalConversations", 0,
                "avgCTR", 0.0,
                "topIntent", "N/A"
            );
            return ResponseEntity.ok(defaultStats);
        }
    }
    
    /**
     * 获取热门建议列表（按点击次数排序）
     * 
     * @return 前5条最常被点击的建议
     */
    @GetMapping("/top-suggestions")
    public ResponseEntity<List<Map<String, Object>>> getTopSuggestions() {
        try {
            List<Map<String, Object>> suggestions = jdbcTemplate.queryForList(
                """
                SELECT
                    suggestion_text as text,
                    COUNT(*) as clicks
                FROM routing_call_logs
                WHERE suggestion_text IS NOT NULL
                    AND clicked = true
                    AND created_at >= NOW() - INTERVAL '30 days'
                GROUP BY suggestion_text
                ORDER BY clicks DESC
                LIMIT 5
                """
            );
            
            log.info("[Analytics] 热门建议查询成功: count={}", suggestions.size());
            return ResponseEntity.ok(suggestions);
            
        } catch (Exception e) {
            log.error("[Analytics] 获取热门建议失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(List.of());
        }
    }
    
    /**
     * 获取 A/B 测试数据
     * 
     * @return Control 组和 Variant 组的对比数据
     */
    @GetMapping("/ab-test")
    public ResponseEntity<Map<String, Object>> getABTestData() {
        try {
            Map<String, Object> result = new HashMap<>();
            
            // Control 组数据
            List<Map<String, Object>> controlData = jdbcTemplate.queryForList(
                """
                SELECT
                    suggestion_position as position,
                    AVG(CASE WHEN clicked = true THEN 100.0 ELSE 0.0 END) as ctr
                FROM routing_call_logs
                WHERE ab_test_group = 'control'
                    AND created_at >= NOW() - INTERVAL '7 days'
                GROUP BY suggestion_position
                ORDER BY suggestion_position
                """
            );
            
            // Variant 组数据
            List<Map<String, Object>> variantData = jdbcTemplate.queryForList(
                """
                SELECT
                    suggestion_position as position,
                    AVG(CASE WHEN clicked = true THEN 100.0 ELSE 0.0 END) as ctr
                FROM routing_call_logs
                WHERE ab_test_group = 'variant'
                    AND created_at >= NOW() - INTERVAL '7 days'
                GROUP BY suggestion_position
                ORDER BY suggestion_position
                """
            );
            
            result.put("control", controlData);
            result.put("variant", variantData);
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("[Analytics] 获取 A/B 测试数据失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of());
        }
    }
    
    /**
     * 获取用户偏好分布
     * 
     * @return 各意图的使用比例
     */
    @GetMapping("/preference-distribution")
    public ResponseEntity<List<Map<String, Object>>> getPreferenceDistribution() {
        try {
            List<Map<String, Object>> distribution = jdbcTemplate.queryForList(
                """
                SELECT
                    routed_agent as intent,
                    COUNT(*) as count,
                    ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2) as percentage
                FROM routing_call_logs
                WHERE created_at >= NOW() - INTERVAL '30 days'
                GROUP BY routed_agent
                ORDER BY count DESC
                """
            );
            
            return ResponseEntity.ok(distribution);
            
        } catch (Exception e) {
            log.error("[Analytics] 获取偏好分布失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(List.of());
        }
    }
    
    /**
     * 获取点击趋势（最近7天）
     * 
     * @return 每日点击次数
     */
    @GetMapping("/click-trend")
    public ResponseEntity<List<Map<String, Object>>> getClickTrend() {
        try {
            List<Map<String, Object>> trend = jdbcTemplate.queryForList(
                """
                SELECT
                    DATE(created_at) as date,
                    COUNT(*) FILTER (WHERE clicked = true) as clicks
                FROM routing_call_logs
                WHERE created_at >= NOW() - INTERVAL '7 days'
                GROUP BY DATE(created_at)
                ORDER BY date
                """
            );
            
            return ResponseEntity.ok(trend);
            
        } catch (Exception e) {
            log.error("[Analytics] 获取点击趋势失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(List.of());
        }
    }
}
