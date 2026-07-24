/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.sql.SqlSecurityValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 配置 - Travel Service
 * <p>
 * 提供核心 SQL 执行工具，供 ReactAgent 调用。
 * 使用 {@link SqlSecurityValidator} 做 AST 级别的 SQL 安全校验，
 * 替代原有的子串匹配方式。
 * <p>
 * 表访问权限通过配置化白名单管理（支持热重载）。
 */
@Configuration("orderMcpServerConfig")
@Slf4j
public class McpServerConfig {

    private static final String SERVICE_NAME = "travel";

    private final JdbcTemplate jdbcTemplate;
    private final McpTableWhitelistConfig whitelistConfig;

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

        // ⭐ 安全检查：基于 AST 解析的精确表名校验
        List<String> allowedTables = whitelistConfig.getAllowedTables(SERVICE_NAME);
        List<String> forbiddenTables = whitelistConfig.getForbiddenTables(SERVICE_NAME);

        SqlSecurityValidator.ValidationResult result =
                SqlSecurityValidator.validateSelect(sql, allowedTables, forbiddenTables);

        if (!result.isValid()) {
            log.warn("[MCP Tool - Travel] ❌ 安全校验未通过: {}", result.getReason());
            log.warn("[MCP Tool - Travel]    SQL: {}", sql);
            throw new IllegalArgumentException(result.getReason());
        }

        log.debug("[MCP Tool - Travel] ✅ 安全校验通过，允许的表: {}", allowedTables);

        try {
            List<Map<String, Object>> queryResult = jdbcTemplate.queryForList(sql);
            log.info("[MCP Tool - Travel] ✅ 查询成功，返回 {} 行", queryResult.size());
            return queryResult;
        } catch (Exception e) {
            log.error("[MCP Tool - Travel] ❌ SQL 执行失败: {}", e.getMessage());
            throw new RuntimeException("SQL 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 travel 相关表的元数据信息
     * 当 Agent 不确定表结构时，可以先调用此工具
     */
    @Tool(description = "获取 travel 相关表的元数据信息")
    public List<Map<String, Object>> getTableSchema() {
        log.info("[MCP Tool - Travel] 执行 getTableSchema");

        List<String> allowedTables = whitelistConfig.getAllowedTables(SERVICE_NAME);

        if (allowedTables.isEmpty()) {
            log.warn("[MCP Tool - Travel] ⚠️ 白名单为空，无法查询表结构");
            return List.of();
        }

        // 使用参数化查询替代字符串拼接
        SqlSecurityValidator.SqlWithArgs inClause = SqlSecurityValidator.buildSafeInClause(allowedTables);

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
                """.formatted(inClause.getPlaceholders());

        return jdbcTemplate.queryForList(sql, inClause.getArgs().toArray());
    }
}
