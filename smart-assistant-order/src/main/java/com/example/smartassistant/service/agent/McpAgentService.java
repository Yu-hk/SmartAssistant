/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.agent;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.prompt.PromptBuilder;
import com.example.smartassistant.config.McpTableWhitelistConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP Agent 服务 - Travel Service
 * 使用 ReactAgent 实现自然语言到 SQL 的自动转换
 */
@Service
@Slf4j
public class McpAgentService {
    
    private final ChatModel chatModel;
    private final JdbcTemplate jdbcTemplate;
    private final McpTableWhitelistConfig whitelistConfig;  // ⭐ 注入白名单配置
    private SmartReActAgent mcpAgent;
    
    public McpAgentService(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            JdbcTemplate jdbcTemplate,
            McpTableWhitelistConfig whitelistConfig) {
        this.chatModel = chatModel;
        this.jdbcTemplate = jdbcTemplate;
        this.whitelistConfig = whitelistConfig;
    }
    
    /**
     * ⭐ 初始化 ReactAgent，只注册 executeQuery 工具
     * Agent 通过自然语言理解，自动生成并执行 SQL
     */
    @PostConstruct
    public void init() {
        log.info("[McpAgentService] 开始创建 ReactAgent...");
        
        try {
            // ⭐ 关键解决：手动创建非代理的工具对象，避免 CGLIB 代理问题
            // ⭐ 传入白名单配置，实现应用层表访问控制
            DatabaseQueryTools tools = new DatabaseQueryTools(jdbcTemplate, whitelistConfig);
            
            // ⭐ 使用 MethodToolCallbackProvider 扫描 @Tool 注解的方法
            MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
            
            ToolCallback[] toolCallbacks = provider.getToolCallbacks();
            log.info("[McpAgentService] ✅ 发现 {} 个 MCP 工具", toolCallbacks.length);
            
            // ⭐ 使用 SmartReActAgent 替代 ReactAgent
            List<ToolCallback> toolList = List.of(toolCallbacks);
            this.mcpAgent = new SmartReActAgent(chatModel)
                .withMaxIterations(10)
                .withTimeoutMs(60_000)
                .withPreset(PromptBuilder.build()
                    .withServicePrompt("""
                    你是一个专业的数据库查询助手。
                    
                    ## 🎯 核心原则
                    1. **必须使用工具**：绝对不要编造数据，必须调用 executeQuery 获取真实数据
                    2. **基于事实回答**：只根据工具返回的数据回答，不要推测或假设
                    3. **简洁清晰**：直接给出关键信息
                    4. **安全第一**：只能生成 SELECT 语句，禁止任何写操作
                    
                    不确定表结构时，先调用 getTableSchema() 查询。
                    SQL 执行失败时，检查语法并重试，最多重试 2 次。
                    """).assemble(), toolList);
            
            log.info("[McpAgentService] ✅ SmartReActAgent 创建成功，已注册 MCP 工具");
            
        } catch (Exception e) {
            log.error("[McpAgentService] ❌ SmartReActAgent 创建失败: {}", e.getMessage(), e);
            this.mcpAgent = null;
        }
    }
    
    /**
     * ⭐ 内部工具类：避免 CGLIB 代理问题
     * 这个类不会被 Spring 管理，所以不会有代理问题
     * 只保留核心的 executeQuery 和 getTableSchema 工具
     */
    static class DatabaseQueryTools {
        
        private final JdbcTemplate jdbcTemplate;
        private final McpTableWhitelistConfig whitelistConfig;
        private static final String CURRENT_SERVICE = "travel"; // ⭐ 当前服务标识
        
        public DatabaseQueryTools(JdbcTemplate jdbcTemplate, McpTableWhitelistConfig whitelistConfig) {
            this.jdbcTemplate = jdbcTemplate;
            this.whitelistConfig = whitelistConfig;
        }
        
        @Tool(description = "执行只读 SQL 查询（仅支持 SELECT 语句）。这是核心工具，用于执行 Agent 生成的 SQL。")
        public java.util.List<java.util.Map<String, Object>> executeQuery(
            @ToolParam(description = "SQL 查询语句（必须是 SELECT 语句）", required = true) String sql
        ) {
            // ⭐ 记录 Agent 生成的 SQL
            log.info("[executeQuery] Agent 生成的 SQL: {}", sql);
            
            // ⭐ 安全检查：只允许 SELECT 语句
            String trimmedSql = sql.trim().toUpperCase();
            if (!trimmedSql.startsWith("SELECT")) {
                log.warn("[executeQuery] ❌ 拒绝非 SELECT 语句: {}", sql);
                throw new IllegalArgumentException("只允许执行 SELECT 查询");
            }
            
            // ⭐ 禁止危险操作：使用单词边界匹配，避免误判（如 INTERVAL 被误认为 CREATE）
            String[] dangerousKeywords = {"DROP ", "DROP\t", "DROP\n", "DELETE ", "DELETE\t", "DELETE\n", 
                                          "UPDATE ", "UPDATE\t", "UPDATE\n", 
                                          "INSERT ", "INSERT\t", "INSERT\n",
                                          "ALTER ", "ALTER\t", "ALTER\n",
                                          "CREATE ", "CREATE\t", "CREATE\n"};
            
            for (String keyword : dangerousKeywords) {
                if (trimmedSql.contains(keyword)) {
                    log.warn("[executeQuery] ❌ 拒绝危险操作");
                    log.warn("[executeQuery]    SQL: {}", sql);
                    log.warn("[executeQuery]    包含危险关键词: {}", keyword.trim());
                    throw new IllegalArgumentException("禁止执行危险操作");
                }
            }
            
            // ⭐ 应用层白名单验证：检查 SQL 中涉及的表是否在白名单中
            validateTableAccess(sql);
            
            try {
                java.util.List<java.util.Map<String, Object>> result = jdbcTemplate.queryForList(sql);
                log.info("[executeQuery] ✅ 查询成功，返回 {} 行", result.size());
                return result;
            } catch (Exception e) {
                log.error("[executeQuery] ❌ SQL 执行失败: {}", e.getMessage());
                throw new RuntimeException("SQL 执行失败: " + e.getMessage(), e);
            }
        }
        
        /**
         * ⭐ 验证 SQL 中的表是否在白名单中
         */
        private void validateTableAccess(String sql) {
            if (whitelistConfig == null) {
                log.warn("[executeQuery] ⚠️  白名单配置未加载，跳过表访问验证");
                return;
            }
            
            // 提取 SQL 中的表名（简单实现，生产环境建议使用 SQL 解析器）
            List<String> tablesInSql = extractTableNames(sql);
            
            for (String tableName : tablesInSql) {
                // 检查黑名单（明确禁止的表）
                List<String> forbiddenTables = whitelistConfig.getForbiddenTables(CURRENT_SERVICE);
                if (forbiddenTables.stream().anyMatch(t -> t.equalsIgnoreCase(tableName))) {
                    log.error("[executeQuery] ❌ 拒绝访问黑名单表: {}", tableName);
                    throw new SecurityException("无权访问表: " + tableName);
                }
                
                // 检查白名单（如果配置了白名单）
                List<String> allowedTables = whitelistConfig.getAllowedTables(CURRENT_SERVICE);
                if (!allowedTables.isEmpty()) {
                    boolean isAllowed = allowedTables.stream()
                        .anyMatch(t -> t.equalsIgnoreCase(tableName));
                    
                    if (!isAllowed) {
                        log.error("[executeQuery] ❌ 拒绝访问不在白名单中的表: {}", tableName);
                        log.error("[executeQuery]    当前服务 '{}' 的白名单: {}", CURRENT_SERVICE, allowedTables);
                        throw new SecurityException("无权访问表: " + tableName + "（不在白名单中）");
                    }
                }
                
                log.debug("[executeQuery] ✅ 表 '{}' 访问验证通过", tableName);
            }
        }
        
        /**
         * ⭐ 从 SQL 中提取表名（简化版）
         * 生产环境建议使用专业的 SQL 解析器如 JSqlParser
         */
        private List<String> extractTableNames(String sql) {
            List<String> tables = new java.util.ArrayList<>();
            
            // 匹配 FROM 子句
            java.util.regex.Pattern fromPattern = java.util.regex.Pattern.compile(
                "\\bFROM\\s+([a-zA-Z_][a-zA-Z0-9_]*)", Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher fromMatcher = fromPattern.matcher(sql);
            while (fromMatcher.find()) {
                String tableName = fromMatcher.group(1).toLowerCase();
                // 排除关键字和系统表
                if (isKeywordOrSystemTable(tableName)) {
                    tables.add(tableName);
                }
            }
            
            // 匹配 JOIN 子句
            java.util.regex.Pattern joinPattern = java.util.regex.Pattern.compile(
                "\\bJOIN\\s+([a-zA-Z_][a-zA-Z0-9_]*)", Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher joinMatcher = joinPattern.matcher(sql);
            while (joinMatcher.find()) {
                String tableName = joinMatcher.group(1).toLowerCase();
                if (isKeywordOrSystemTable(tableName)) {
                    tables.add(tableName);
                }
            }
            
            return tables.stream().distinct().collect(java.util.stream.Collectors.toList());
        }
        
        /**
         * 判断是否为关键字或系统表
         */
        private boolean isKeywordOrSystemTable(String name) {
            Set<String> keywords = Set.of(
                "select", "from", "where", "and", "or", "not", "in", "on",
                "join", "left", "right", "inner", "outer", "cross",
                "group", "order", "by", "having", "limit", "offset",
                "as", "distinct", "count", "sum", "avg", "min", "max",
                "information_schema", "pg_catalog"
            );
            return !keywords.contains(name.toLowerCase());
        }
        
        @Tool(description = "获取数据库中所有表的元数据信息。当不确定表结构时，先调用此工具。")
        public java.util.List<java.util.Map<String, Object>> getTableSchema() {
            String sql = """
                SELECT
                    table_name,
                    column_name,
                    data_type,
                    is_nullable,
                    column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                ORDER BY table_name, ordinal_position
                """;
            
            return jdbcTemplate.queryForList(sql);
        }
    }
    
    /**
     * 执行自然语言查询
     * SmartReActAgent 内置超时保护、迭代限制、Token 预算追踪
     */
    public String query(String question) {
        if (mcpAgent == null) {
            throw new IllegalStateException("SmartReActAgent 未初始化");
        }
        
        log.info("[MCP Agent] 开始查询: {}", question);
        long startTime = System.currentTimeMillis();
        
        try {
            // ⭐ SmartReActAgent 内置超时 + 迭代限制
            String result = mcpAgent.execute(question);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("[MCP Agent] 查询完成 (耗时: {} ms): {}", duration, 
                result != null ? result.substring(0, Math.min(100, result.length())) : "null");
            
            return result;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[MCP Agent] 查询失败 (耗时: {} ms): {}", duration, e.getMessage(), e);
            throw new RuntimeException("MCP Agent 查询失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查 Agent 是否可用
     */
    public boolean isAvailable() {
        return mcpAgent != null;
    }
}
