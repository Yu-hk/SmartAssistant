package com.example.smartassistant.consumer.service.monitoring;

import com.example.smartassistant.consumer.service.cache.SqlQueryCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * SQL 性能监控服务
 * 监控慢查询、索引使用情况、执行计划等
 */
@Service
@Slf4j
public class SqlPerformanceMonitor {

    private final JdbcTemplate jdbcTemplate;
    private final SqlQueryCache sqlQueryCache;  // ⭐ SQL 查询缓存
    private final MeterRegistry meterRegistry;  // ⭐ Prometheus 指标注册器

    // Prometheus 计数器（Agent SQL 执行成功/失败）
    private final Counter agentSqlSuccessCounter;
    private final Counter agentSqlFailureCounter;

    public SqlPerformanceMonitor(JdbcTemplate jdbcTemplate,
                                  SqlQueryCache sqlQueryCache,
                                  MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqlQueryCache = sqlQueryCache;
        this.meterRegistry = meterRegistry;

        // 初始化计数器
        this.agentSqlSuccessCounter = Counter.builder("sql.agent.execution.success")
                .description("Number of successful Agent SQL executions")
                .tag("service", "consumer-service")
                .register(meterRegistry);
        this.agentSqlFailureCounter = Counter.builder("sql.agent.execution.failure")
                .description("Number of failed Agent SQL executions")
                .tag("service", "consumer-service")
                .register(meterRegistry);
    }
    
    // ⭐ SQL 监控阈值配置
    @org.springframework.beans.factory.annotation.Value("${sql.monitor.slow-query-threshold-ms:1000}")
    private long slowQueryThresholdMs;
    
    @org.springframework.beans.factory.annotation.Value("${sql.monitor.index-usage-min-scans:100}")
    private int indexUsageMinScans;
    
    @org.springframework.beans.factory.annotation.Value("${sql.monitor.index-usage-low-threshold:50}")
    private double indexUsageLowThreshold;
    
    /**
     * 定期检查慢查询（可配置间隔）
     * 从 pg_stat_statements 中查询执行时间超过阈值的查询
     */
    @Scheduled(fixedRateString = "${scheduler.sql-performance-check-interval:300000}")
    public void checkSlowQueries() {
        try {
            // 首先检查 pg_stat_statements 扩展是否启用
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_stat_statements'", Integer.class);
            
            if (count == null || count == 0) {
                log.info("[SQL Performance] pg_stat_statements 扩展未启用，跳过慢查询检查");
                return;
            }

            String sql = """
                SELECT
                    query,
                    calls,
                    total_exec_time,
                    mean_exec_time,
                    max_exec_time,
                    rows,
                    100.0 * shared_blks_hit / nullif(shared_blks_hit + shared_blks_read, 0) AS hit_percent
                FROM pg_stat_statements
                WHERE mean_exec_time > ?
                ORDER BY mean_exec_time DESC
                LIMIT 10
                """;
            
            List<Map<String, Object>> slowQueries = jdbcTemplate.queryForList(sql, slowQueryThresholdMs);
            
            if (!slowQueries.isEmpty()) {
                log.warn("[SQL Performance] 发现 {} 个慢查询:", slowQueries.size());
                for (Map<String, Object> query : slowQueries) {
                    log.warn("  - 查询: {}", truncate(query.get("query")));
                    log.warn("    调用次数: {}, 平均耗时: {} ms, 最大耗时: {} ms", 
                        query.get("calls"), 
                        ((Number) query.get("mean_exec_time")).longValue(),
                        ((Number) query.get("max_exec_time")).longValue());
                    log.warn("    缓存命中率: {}%", Math.round(((Number) query.get("hit_percent")).doubleValue()));
                }
            } else {
                log.info("[SQL Performance] 未发现慢查询");
            }
            
        } catch (Exception e) {
            log.warn("[SQL Performance] 检查慢查询失败: {}", e.getMessage());
        }
    }
    
    /**
     * 定期检查索引使用情况（可配置间隔）
     * 监控索引命中率和未使用的索引
     */
    @Scheduled(fixedRateString = "${scheduler.sql-slow-query-analysis-interval:3600000}")
    public void checkIndexUsage() {
        try {
            // 检查索引命中率
            String hitRateSql = """
                SELECT
                    schemaname || '.' || relname AS table_name,
                    idx_scan AS index_scans,
                    seq_scan AS sequential_scans,
                    CASE
                        WHEN idx_scan + seq_scan > 0
                        THEN round(100.0 * idx_scan / (idx_scan + seq_scan), 2)
                        ELSE 0
                    END AS index_usage_percent
                FROM pg_stat_user_tables
                WHERE idx_scan + seq_scan > ?
                ORDER BY index_usage_percent ASC
                LIMIT 10
                """;
            
            List<Map<String, Object>> tables = jdbcTemplate.queryForList(hitRateSql, indexUsageMinScans);
            
            log.info("[SQL Performance] 索引使用率统计:");
            for (Map<String, Object> table : tables) {
                Number usageNum = (Number) table.get("index_usage_percent");
                double usagePercent = usageNum != null ? usageNum.doubleValue() : 0.0;
                if (usagePercent < indexUsageLowThreshold) {
                    log.warn("  {}: 索引使用率 {}% (索引扫描: {}, 全表扫描: {})",
                        table.get("table_name"),
                        usagePercent,
                        table.get("index_scans"),
                        table.get("sequential_scans"));
                } else {
                    log.info("  {}: 索引使用率 {}%", 
                        table.get("table_name"), 
                        usagePercent);
                }
            }
            
            // 检查未使用的索引
            String unusedIndexesSql = """
                SELECT
                    schemaname || '.' || tablename AS table_name,
                    indexrelname AS index_name,
                    idx_scan AS scans
                FROM pg_stat_user_indexes
                WHERE idx_scan = 0
                    AND indexrelname NOT LIKE '%pkey%'
                ORDER BY schemaname, tablename
                """;
            
            List<Map<String, Object>> unusedIndexes = jdbcTemplate.queryForList(unusedIndexesSql);
            
            if (!unusedIndexes.isEmpty()) {
                log.warn("[SQL Performance] 发现 {} 个未使用的索引:", unusedIndexes.size());
                for (Map<String, Object> index : unusedIndexes) {
                    log.warn("  - {}.{} (扫描次数: 0)", 
                        index.get("table_name"), 
                        index.get("index_name"));
                }
            }
            
        } catch (Exception e) {
            log.warn("[SQL Performance] 检查索引使用情况失败: {}", e.getMessage());
        }
    }
    
    /**
     * 分析特定表的索引统计信息（每天凌晨2点）
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void analyzeTableStatistics() {
        String[] tablesToAnalyze = {"users", "conversation_feedback", "routing_call_log"};
        
        for (String table : tablesToAnalyze) {
            try {
                // 更新统计信息
                jdbcTemplate.execute("ANALYZE " + table);
                
                // 获取表大小和行数
                String statsSql = """
                    SELECT
                        relname AS table_name,
                        n_live_tup AS row_count,
                        pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
                        last_vacuum,
                        last_analyze
                    FROM pg_stat_user_tables
                    WHERE relname = ?;
                    """;
                
                List<Map<String, Object>> stats = jdbcTemplate.queryForList(statsSql, table);
                
                if (!stats.isEmpty()) {
                    Map<String, Object> stat = stats.get(0);
                    log.info("[SQL Performance] 📈 表统计 - {}: 行数={}, 大小={}, 最后分析={}",
                        stat.get("table_name"),
                        stat.get("row_count"),
                        stat.get("total_size"),
                        stat.get("last_analyze"));
                }
                
            } catch (Exception e) {
                log.error("[SQL Performance] 分析表 {} 失败: {}", table, e.getMessage());
            }
        }
    }
    
    /**
     * 刷新物化视图（每天凌晨3点）
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void refreshMaterializedViews() {
        try {
            // 检查是否存在物化视图
            String checkSql = "SELECT COUNT(*) FROM pg_matviews";
            Integer mvCount = jdbcTemplate.queryForObject(checkSql, Integer.class);
            
            if (mvCount == null || mvCount == 0) {
                log.info("[SQL Performance] 不存在物化视图，跳过刷新");
                return;
            }

            log.info("[SQL Performance] 开始刷新物化视图...");
            
            // 获取所有物化视图并逐个刷新
            String refreshSql = """
                SELECT schemaname, matviewname 
                FROM pg_matviews 
                ORDER BY schemaname, matviewname
                """;
            List<Map<String, Object>> mvs = jdbcTemplate.queryForList(refreshSql);
            
            for (Map<String, Object> mv : mvs) {
                String fullName = mv.get("schemaname") + "." + mv.get("matviewname");
                jdbcTemplate.execute("REFRESH MATERIALIZED VIEW " + fullName);
                log.info("  刷新: {}", fullName);
            }
            
            log.info("[SQL Performance] 物化视图刷新完成，共刷新 {} 个");
            
            // 刷新后清除相关缓存
            sqlQueryCache.clearAll();
            log.info("[SQL Performance] 已清除 SQL 缓存");
            
        } catch (Exception e) {
            log.warn("[SQL Performance] 刷新物化视图失败: {}", e.getMessage());
        }
    }
    
    /**
     * 获取查询执行计划（用于调试）
     * @param sql 要分析的 SQL 语句
     * @return 执行计划详情
     */
    public String getExecutionPlan(String sql) {
        try {
            String explainSql = "EXPLAIN ANALYZE " + sql;
            List<Map<String, Object>> plan = jdbcTemplate.queryForList(explainSql);
            
            StringBuilder result = new StringBuilder();
            for (Map<String, Object> row : plan) {
                result.append(row.values().iterator().next()).append("\n");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            log.error("[SQL Performance] 获取执行计划失败: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
    
    /**
     * 记录 Agent SQL 质量指标
     * ⭐ 同时将指标上报到 Prometheus
     * @param sql Agent 生成的 SQL
     * @param duration 执行时间（毫秒）
     * @param success 是否成功
     * @param rowCount 返回行数
     */
    public void recordAgentSqlMetrics(String sql, long duration, boolean success, int rowCount) {
        try {
            // 记录到日志
            if (duration > 1000) {
                log.warn("[Agent SQL Quality] ⚠️ 慢查询: 耗时={}ms, SQL={}",
                    duration, truncate(sql));
            } else {
                log.info("[Agent SQL Quality] ✅ 查询成功: 耗时={}ms, 返回{}行",
                    duration, rowCount);
            }

            // ⭐ 上报到 Prometheus
            // 1. Timer：记录 SQL 执行耗时（按成功/失败分标签）
            Timer.builder("sql.agent.execution.duration")
                    .description("Agent SQL execution duration in milliseconds")
                    .tag("service", "consumer-service")
                    .tag("success", String.valueOf(success))
                    .register(meterRegistry)
                    .record(duration, TimeUnit.MILLISECONDS);

            // 2. Counter：记录成功/失败次数
            if (success) {
                agentSqlSuccessCounter.increment();
            } else {
                agentSqlFailureCounter.increment();
            }

            // 3. Gauge：记录返回行数（仅成功时）
            if (success) {
                meterRegistry.gauge("sql.agent.execution.rows",
                        List.of(io.micrometer.core.instrument.Tag.of("service", "consumer-service")),
                        rowCount);
            }

        } catch (Exception e) {
            log.error("[Agent SQL Quality] 记录指标失败: {}", e.getMessage());
        }
    }
    
    /**
     * 获取数据库整体健康状态
     */
    public Map<String, Object> getDatabaseHealth() {
        try {
            String healthSql = """
                SELECT
                    count(*) AS total_tables,
                    sum(n_live_tup) AS total_rows,
                    pg_size_pretty(sum(pg_total_relation_size(relid))) AS total_size
                FROM pg_stat_user_tables;
                """;
            
            List<Map<String, Object>> result = jdbcTemplate.queryForList(healthSql);
            
            if (!result.isEmpty()) {
                return result.get(0);
            }
            
        } catch (Exception e) {
            log.error("[SQL Performance] 获取数据库健康状态失败: {}", e.getMessage());
        }
        
        return Map.of("status", "error");
    }
    
    /**
     * 截断字符串
     */
    private String truncate(Object obj) {
        if (obj == null) return "null";
        String str = obj.toString();
        return str.length() > 100 ? str.substring(0, 100) + "..." : str;
    }
}
