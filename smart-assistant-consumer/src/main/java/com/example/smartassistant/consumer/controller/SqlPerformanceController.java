package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.monitoring.SqlPerformanceMonitor;
import com.example.smartassistant.consumer.service.monitoring.SqlReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * SQL 性能监控端点
 * 提供数据库性能监控和诊断功能
 */
@RestController
@RequestMapping("/api/monitor/sql")
@Slf4j
public class SqlPerformanceController {
    
    private final SqlPerformanceMonitor performanceMonitor;
    private final SqlReviewService sqlReviewService;  // ⭐ SQL 审查服务
    
    public SqlPerformanceController(SqlPerformanceMonitor performanceMonitor, 
                                    SqlReviewService sqlReviewService) {
        this.performanceMonitor = performanceMonitor;
        this.sqlReviewService = sqlReviewService;
    }
    
    /**
     * 获取数据库健康状态
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getDatabaseHealth() {
        try {
            Map<String, Object> health = performanceMonitor.getDatabaseHealth();
            return ResponseEntity.ok(health);
        } catch (Exception e) {
            log.error("获取数据库健康状态失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 分析 SQL 执行计划
     */
    @PostMapping("/explain")
    public ResponseEntity<Map<String, Object>> explainQuery(@RequestBody Map<String, String> request) {
        try {
            String sql = request.get("sql");
            
            if (sql == null || sql.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "SQL 不能为空"));
            }
            
            // 安全检查：只允许 SELECT
            String trimmedSql = sql.trim().toUpperCase();
            if (!trimmedSql.startsWith("SELECT")) {
                return ResponseEntity.badRequest().body(Map.of("error", "只允许执行 SELECT 查询"));
            }
            
            String executionPlan = performanceMonitor.getExecutionPlan(sql);
            
            Map<String, Object> result = new HashMap<>();
            result.put("sql", sql);
            result.put("plan", executionPlan);
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("分析执行计划失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 手动触发索引使用情况检查
     */
    @PostMapping("/check-indexes")
    public ResponseEntity<Map<String, String>> checkIndexes() {
        try {
            performanceMonitor.checkIndexUsage();
            return ResponseEntity.ok(Map.of("status", "success", "message", "索引检查已完成，请查看日志"));
        } catch (Exception e) {
            log.error("检查索引使用情况失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 手动触发慢查询检查
     */
    @PostMapping("/check-slow-queries")
    public ResponseEntity<Map<String, String>> checkSlowQueries() {
        try {
            performanceMonitor.checkSlowQueries();
            return ResponseEntity.ok(Map.of("status", "success", "message", "慢查询检查已完成，请查看日志"));
        } catch (Exception e) {
            log.error("检查慢查询失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 手动更新表统计信息
     */
    @PostMapping("/analyze-tables")
    public ResponseEntity<Map<String, String>> analyzeTables() {
        try {
            performanceMonitor.analyzeTableStatistics();
            return ResponseEntity.ok(Map.of("status", "success", "message", "表统计信息已更新"));
        } catch (Exception e) {
            log.error("更新表统计信息失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 审查 SQL 语句
     */
    @PostMapping("/review")
    public ResponseEntity<SqlReviewService.ReviewResult> reviewSql(@RequestBody Map<String, String> request) {
        try {
            String sql = request.get("sql");
            
            if (sql == null || sql.trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            
            SqlReviewService.ReviewResult result = sqlReviewService.review(sql);
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("SQL 审查失败: {}", e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * 批量审查 SQL
     */
    @PostMapping("/review-batch")
    public ResponseEntity<Map<String, Object>> reviewBatchSql(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            java.util.List<String> sqlList = (java.util.List<String>) request.get("sqlList");
            
            if (sqlList == null || sqlList.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "SQL 列表不能为空"));
            }
            
            java.util.List<SqlReviewService.ReviewResult> results = sqlReviewService.reviewBatch(sqlList);
            SqlReviewService.ReviewStatistics stats = sqlReviewService.getStatistics(results);
            
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("results", results);
            response.put("statistics", stats);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("批量 SQL 审查失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
