/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQL 性能指标收集器
 * 将数据库性能指标暴露给 Prometheus
 */
@Component
@Slf4j
public class SqlMetricsCollector {
    
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    
    // 指标计数器
    private final AtomicLong slowQueryCount = new AtomicLong(0);
    private final AtomicLong cacheHitCount = new AtomicLong(0);
    private final AtomicLong cacheMissCount = new AtomicLong(0);
    private final AtomicLong agentSqlSuccessCount = new AtomicLong(0);
    private final AtomicLong agentSqlFailureCount = new AtomicLong(0);
    
    public SqlMetricsCollector(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        
        // 注册指标
        registerMetrics();
    }
    
    /**
     * 注册 Prometheus 指标
     */
    private void registerMetrics() {
        // 慢查询数量
        Gauge.builder("sql.slow_queries.count", slowQueryCount, AtomicLong::get)
            .description("Number of slow queries detected")
            .register(meterRegistry);
        
        // 缓存命中数
        Gauge.builder("sql.cache.hits.count", cacheHitCount, AtomicLong::get)
            .description("Number of SQL cache hits")
            .register(meterRegistry);
        
        // 缓存未命中数
        Gauge.builder("sql.cache.misses.count", cacheMissCount, AtomicLong::get)
            .description("Number of SQL cache misses")
            .register(meterRegistry);
        
        // Agent SQL 成功数
        Gauge.builder("sql.agent.success.count", agentSqlSuccessCount, AtomicLong::get)
            .description("Number of successful Agent SQL executions")
            .register(meterRegistry);
        
        // Agent SQL 失败数
        Gauge.builder("sql.agent.failure.count", agentSqlFailureCount, AtomicLong::get)
            .description("Number of failed Agent SQL executions")
            .register(meterRegistry);
    }
    
    /**
     * 定期收集数据库指标（可配置间隔）
     */
    @Scheduled(fixedRateString = "${scheduler.sql-metrics-interval:60000}")
    public void collectDatabaseMetrics() {
        try {
            // 1. 连接池状态
            collectConnectionPoolMetrics();
            
            // 2. 表大小和行数
            collectTableMetrics();
            
            // 3. 索引使用率
            collectIndexMetrics();
            
            // 4. 缓存命中率
            collectCacheHitRate();
            
            log.debug("[SQL Metrics] 指标收集完成");
            
        } catch (Exception e) {
            log.error("[SQL Metrics] 收集指标失败: {}", e.getMessage());
        }
    }
    
    /**
     * 收集连接池指标
     */
    private void collectConnectionPoolMetrics() {
        try {
            String sql = """
                SELECT
                    count(*) as total_connections,
                    count(*) FILTER (WHERE state = 'active') as active_connections,
                    count(*) FILTER (WHERE state = 'idle') as idle_connections,
                    count(*) FILTER (WHERE state = 'waiting') as waiting_connections
                FROM pg_stat_activity
                WHERE datname = current_database();
                """;
            
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);
            if (!result.isEmpty()) {
                Map<String, Object> stats = result.get(0);
                
                meterRegistry.gauge("db.connections.total", 
                    ((Number) stats.get("total_connections")).doubleValue());
                meterRegistry.gauge("db.connections.active", 
                    ((Number) stats.get("active_connections")).doubleValue());
                meterRegistry.gauge("db.connections.idle", 
                    ((Number) stats.get("idle_connections")).doubleValue());
                meterRegistry.gauge("db.connections.waiting", 
                    ((Number) stats.get("waiting_connections")).doubleValue());
            }
            
        } catch (Exception e) {
            log.warn("收集连接池指标失败: {}", e.getMessage());
        }
    }
    
    /**
     * 收集表指标
     */
    private void collectTableMetrics() {
        try {
            String sql = """
                SELECT
                    relname as table_name,
                    n_live_tup as row_count,
                    pg_total_relation_size(relid) as size_bytes,
                    seq_scan as sequential_scans,
                    idx_scan as index_scans
                FROM pg_stat_user_tables
                ORDER BY n_live_tup DESC
                LIMIT 10;
                """;
            
            List<Map<String, Object>> tables = jdbcTemplate.queryForList(sql);
            
            for (Map<String, Object> table : tables) {
                String tableName = (String) table.get("table_name");
                if (tableName == null) continue;
                
                List<Tag> tags = List.of(Tag.of("table", tableName));
                
                Number rowCount = (Number) table.get("row_count");
                Number sizeBytes = (Number) table.get("size_bytes");
                Number seqScans = (Number) table.get("sequential_scans");
                Number idxScans = (Number) table.get("index_scans");
                
                if (rowCount != null) {
                    meterRegistry.gauge("db.table.rows", tags, rowCount.doubleValue());
                }
                if (sizeBytes != null) {
                    meterRegistry.gauge("db.table.size_bytes", tags, sizeBytes.doubleValue());
                }
                if (seqScans != null) {
                    meterRegistry.gauge("db.table.seq_scans", tags, seqScans.doubleValue());
                }
                if (idxScans != null) {
                    meterRegistry.gauge("db.table.idx_scans", tags, idxScans.doubleValue());
                }
            }
            
        } catch (Exception e) {
            log.warn("收集表指标失败: {}", e.getMessage());
        }
    }
    
    /**
     * 收集索引指标
     */
    private void collectIndexMetrics() {
        try {
            String sql = """
                SELECT
                    schemaname || '.' || tablename as table_name,
                    indexrelname as index_name,
                    idx_scan as scans,
                    idx_tup_read as tuples_read,
                    idx_tup_fetch as tuples_fetched
                FROM pg_stat_user_indexes
                ORDER BY idx_scan DESC
                LIMIT 20;
                """;
            
            List<Map<String, Object>> indexes = jdbcTemplate.queryForList(sql);
            
            for (Map<String, Object> index : indexes) {
                String tableName = (String) index.get("table_name");
                String indexName = (String) index.get("index_name");
                if (tableName == null || indexName == null) continue;
                
                List<Tag> tags = List.of(
                    Tag.of("table", tableName),
                    Tag.of("index", indexName)
                );
                
                Number scans = (Number) index.get("scans");
                Number tuplesRead = (Number) index.get("tuples_read");
                Number tuplesFetched = (Number) index.get("tuples_fetched");
                
                if (scans != null) {
                    meterRegistry.gauge("db.index.scans", tags, scans.doubleValue());
                }
                if (tuplesRead != null) {
                    meterRegistry.gauge("db.index.tuples_read", tags, tuplesRead.doubleValue());
                }
                if (tuplesFetched != null) {
                    meterRegistry.gauge("db.index.tuples_fetched", tags, tuplesFetched.doubleValue());
                }
            }
            
        } catch (Exception e) {
            log.warn("收集索引指标失败: {}", e.getMessage());
        }
    }
    
    /**
     * 收集缓存命中率
     */
    private void collectCacheHitRate() {
        try {
            String sql = """
                SELECT
                    sum(heap_blks_hit) as cache_hits,
                    sum(heap_blks_read) as cache_misses,
                    CASE
                        WHEN sum(heap_blks_hit + heap_blks_read) > 0
                        THEN round(100.0 * sum(heap_blks_hit) / sum(heap_blks_hit + heap_blks_read), 2)
                        ELSE 0
                    END as hit_rate
                FROM pg_statio_user_tables;
                """;
            
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);
            if (!result.isEmpty()) {
                Map<String, Object> stats = result.get(0);
                
                Number cacheHits = (Number) stats.get("cache_hits");
                Number cacheMisses = (Number) stats.get("cache_misses");
                Number hitRate = (Number) stats.get("hit_rate");
                
                if (cacheHits != null) {
                    meterRegistry.gauge("db.cache.hits", cacheHits.doubleValue());
                }
                if (cacheMisses != null) {
                    meterRegistry.gauge("db.cache.misses", cacheMisses.doubleValue());
                }
                if (hitRate != null) {
                    meterRegistry.gauge("db.cache.hit_rate_percent", hitRate.doubleValue());
                }
            }
            
        } catch (Exception e) {
            log.warn("收集缓存命中率失败: {}", e.getMessage());
        }
    }
    
    // ==================== 手动更新计数器 ====================
    
    public void incrementSlowQueryCount() {
        slowQueryCount.incrementAndGet();
    }
    
    public void incrementCacheHitCount() {
        cacheHitCount.incrementAndGet();
    }
    
    public void incrementCacheMissCount() {
        cacheMissCount.incrementAndGet();
    }
    
    public void incrementAgentSqlSuccessCount() {
        agentSqlSuccessCount.incrementAndGet();
    }
    
    public void incrementAgentSqlFailureCount() {
        agentSqlFailureCount.incrementAndGet();
    }
}
