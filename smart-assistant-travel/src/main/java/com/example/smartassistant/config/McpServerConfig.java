package com.example.smartassistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 配置 - Travel Service
 * ⭐ 提供核心 SQL 执行工具，供 ReactAgent 调用
 * ⭐ 使用配置化白名单管理表访问权限（支持热重载）
 */
@Component
@Configuration
@Slf4j
public class McpServerConfig {
    
    private final JdbcTemplate jdbcTemplate;
    private final McpTableWhitelistConfig whitelistConfig;  // ⭐ 使用配置类
    
    public McpServerConfig(JdbcTemplate jdbcTemplate, McpTableWhitelistConfig whitelistConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.whitelistConfig = whitelistConfig;
    }
    
    /**
     * 执行只读 SQL 查询（核心工具 - 限制表访问权限）
     * Agent 通过自然语言理解，自动生成并执行 SQL
     */
    @Tool(description = "执行只读 SQL 查询（仅支持 SELECT 语句，仅限 travel 相关表）")
    public List<Map<String, Object>> executeQuery(
        @ToolParam(description = "SQL 查询语句（仅支持 SELECT）", required = true) String sql
    ) {
        log.info("[MCP Tool - Travel] ⭐ Agent 生成的 SQL: {}", sql);
        
        // ⭐ 安全检查1：只允许 SELECT 语句
        String trimmedSql = sql.trim().toUpperCase();
        if (!trimmedSql.startsWith("SELECT")) {
            log.warn("[MCP Tool - Travel] ❌ 拒绝非 SELECT 语句");
            throw new IllegalArgumentException("只允许执行 SELECT 查询");
        }
        
        // ⭐ 安全检查2：禁止危险操作
        String[] dangerousKeywords = {"DROP ", "DROP\t", "DROP\n", "DELETE ", "DELETE\t", "DELETE\n", 
                                      "UPDATE ", "UPDATE\t", "UPDATE\n", 
                                      "INSERT ", "INSERT\t", "INSERT\n",
                                      "ALTER ", "ALTER\t", "ALTER\n",
                                      "CREATE ", "CREATE\t", "CREATE\n"};
        
        for (String keyword : dangerousKeywords) {
            if (trimmedSql.contains(keyword)) {
                log.warn("[MCP Tool - Travel] ❌ 拒绝危险操作");
                log.warn("[MCP Tool - Travel]    SQL: {}", sql);
                log.warn("[MCP Tool - Travel]    包含危险关键词: {}", keyword.trim());
                throw new IllegalArgumentException("禁止执行危险操作");
            }
        }
        
        // ⭐ 安全检查3：限制可访问的表（从配置文件读取白名单）
        String serviceName = "travel";  // 当前服务名称
        boolean hasAllowedTable = false;
        String lowerSql = sql.toLowerCase();
        
        // 检查是否包含白名单中的表
        for (String table : whitelistConfig.getAllowedTables(serviceName)) {
            if (lowerSql.contains(table.toLowerCase())) {
                hasAllowedTable = true;
                break;
            }
        }
        
        // 检查是否访问了黑名单中的表
        for (String table : whitelistConfig.getForbiddenTables(serviceName)) {
            if (lowerSql.contains(table.toLowerCase())) {
                log.warn("[MCP Tool - Travel] ❌ 拒绝访问未授权的表: {}", table);
                log.warn("[MCP Tool - Travel]    SQL: {}", sql);
                throw new IllegalArgumentException("无权访问表: " + table);
            }
        }
        
        if (!hasAllowedTable) {
            log.warn("[MCP Tool - Travel] ⚠️  SQL 未包含任何允许的表");
            log.warn("[MCP Tool - Travel]    SQL: {}", sql);
            log.warn("[MCP Tool - Travel]    允许的表: {}", whitelistConfig.getAllowedTables(serviceName));
            throw new IllegalArgumentException("只能查询 travel 相关的表");
        }
        
        try {
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);
            log.info("[MCP Tool - Travel] ✅ 查询成功，返回 {} 行", result.size());
            return result;
        } catch (Exception e) {
            log.error("[MCP Tool - Travel] ❌ SQL 执行失败: {}", e.getMessage());
            throw new RuntimeException("SQL 执行失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取数据库表结构（辅助工具 - 仅返回 travel 相关表）
     * 当 Agent 不确定表结构时，可以先调用此工具
     */
    @Tool(description = "获取 travel 相关表的元数据信息")
    public List<Map<String, Object>> getTableSchema() {
        log.info("[MCP Tool - Travel] 执行 getTableSchema");
            
        // ⭐ 从配置文件读取白名单，动态构建查询
        String serviceName = "travel";
        List<String> allowedTables = whitelistConfig.getAllowedTables(serviceName);
        
        if (allowedTables.isEmpty()) {
            log.warn("[MCP Tool - Travel] ⚠️  白名单为空，无法查询表结构");
            return List.of();
        }
        
        // 构建 IN 子句
        String tablesInClause = allowedTables.stream()
                .map(table -> "'" + table + "'")
                .reduce((a, b) -> a + ", " + b)
                .orElse("''");
        
        String sql = """
            SELECT
                cols.table_name,
                cols.column_name,
                cols.data_type,
                cols.is_nullable,
                cols.column_default
            FROM information_schema.columns cols
            WHERE cols.table_schema = 'public'
                AND cols.table_name IN (%s)
            ORDER BY cols.table_name, cols.ordinal_position;
            """.formatted(tablesInClause);
            
        return jdbcTemplate.queryForList(sql);
    }
}
