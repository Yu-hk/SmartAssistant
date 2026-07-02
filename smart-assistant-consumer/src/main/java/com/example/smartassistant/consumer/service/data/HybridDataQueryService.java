/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.data;

import com.example.smartassistant.consumer.service.agent.McpAgentService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;  // ⭐ 正确包名
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据查询服务 - 混合架构
 * 结合 MyBatis（核心业务）和 MCP（灵活查询）
 */
@Service
public class HybridDataQueryService {

    private static final Logger log = LoggerFactory.getLogger(HybridDataQueryService.class);
    
    private final ChatClient mcpChatClient;  // MCP Client（备用）
    private final McpAgentService mcpAgentService;  // ⭐ ReactAgent 服务
    private final SyncMcpToolCallbackProvider syncMcpToolCallbackProvider;  // ⭐ MCP 专用类型
    private final JdbcTemplate jdbcTemplate;  // ⭐ JdbcTemplate
    private volatile boolean mcpInitialized = false;  // MCP 初始化标志

    @Value("${spring.ai.mcp.server.enabled:true}")
    private boolean mcpEnabled;  // ⭐ 从配置文件读取 MCP Server 是否启用

    /**
     * 构造函数：注入依赖并初始化
     */
    public HybridDataQueryService(
            ChatClient.Builder chatClientBuilder,
            @Autowired(required = false) SyncMcpToolCallbackProvider syncMcpToolCallbackProvider,
            McpAgentService mcpAgentService,
            JdbcTemplate jdbcTemplate) {

        this.syncMcpToolCallbackProvider = syncMcpToolCallbackProvider;
        this.mcpAgentService = mcpAgentService;
        this.jdbcTemplate = jdbcTemplate;

        // 构建备用的 ChatClient
        if (syncMcpToolCallbackProvider != null) {
            log.info("[HybridDataQueryService] 发现 SyncMcpToolCallbackProvider，注册 MCP 工具");
            this.mcpChatClient = chatClientBuilder
                .defaultTools((Object[]) syncMcpToolCallbackProvider.getToolCallbacks())
                .build();
        } else {
            log.warn("[HybridDataQueryService] 未发现 SyncMcpToolCallbackProvider");
            this.mcpChatClient = chatClientBuilder.build();
        }
        log.info("[HybridDataQueryService] 初始化完成，ReactAgent 可用: {}", mcpAgentService.isAvailable());
    }
    
    /**
     * 服务启动后预热 MCP Server
     * ⭐ 因为我们使用的是内嵌 ReactAgent，不需要外部 MCP Server 预热
     */
    @PostConstruct
    public void warmupMcpServer() {
        // ⭐ 如果 MCP Server 未启用，跳过预热
        if (!mcpEnabled) {
            log.info("[MCP Warmup] MCP Server 已禁用，跳过预热");
            mcpInitialized = false;
            return;
        }
        
        // ⭐ 因为使用内嵌 ReactAgent，直接标记为已初始化
        mcpInitialized = true;
        log.info("[MCP Warmup] ✅ MCP Server (ReactAgent) 已就绪");
    }
    
    // ==================== 方式 1：MyBatis（核心业务）====================

    // ==================== 方式 2：MCP（灵活查询）====================
    
    /**
     * 自然语言数据查询（使用 ReactAgent + 智能降级）
     * ⭐ ReactAgent 会自动执行 Think-Act-Observe 循环，真正调用 MCP 工具
     */
    public Map<String, Object> naturalLanguageQuery(String question) {
        long startTime = System.currentTimeMillis();
        
        // ⭐ 如果 MCP 未启用或未初始化，直接使用降级方案
        if (!mcpEnabled || !mcpInitialized) {
            log.info("[数据查询] MCP 未启用或未初始化，使用 MyBatis 降级方案 (enabled={}, initialized={})", mcpEnabled, mcpInitialized);
            return fallbackToMyBatis(question, "MCP 已禁用或未初始化");
        }
        
        try {
            // ⭐ 优先使用 ReactAgent（自动工具调用循环）
            if (mcpAgentService.isAvailable()) {
                log.info("[数据查询] 使用 ReactAgent 执行查询: {}", question);
                
                String answer = mcpAgentService.query(question);
                
                long duration = System.currentTimeMillis() - startTime;
                log.info("[数据查询] ReactAgent 返回结果 (耗时: {} ms): {}", duration, 
                    answer != null ? answer.substring(0, Math.min(100, answer.length())) : "null");
                
                // 验证结果有效性
                if (isInvalidResponse(answer)) {
                    log.warn("[数据查询] ⚠️ ReactAgent 返回无效响应，触发降级机制");
                    return fallbackToMyBatis(question, "ReactAgent 返回无效结果");
                }
                
                // 格式化答案
                String formattedAnswer = formatAnswer(answer);
                
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("question", question);
                result.put("answer", formattedAnswer);
                result.put("method", "ReactAgent-MCP");
                result.put("duration_ms", duration);
                
                return result;
            }
            
            // 备用方案：使用 ChatClient
            log.warn("[数据查询] ReactAgent 不可用，回退到 ChatClient 模式");
            return queryWithChatClient(question, startTime);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[数据查询] ❌ 查询失败 (耗时: {} ms): {}", duration, e.getMessage(), e);
            
            // ⭐ 自动降级到 MyBatis
            return fallbackToMyBatis(question, e.getMessage());
        }
    }
    
    /**
     * 使用 ChatClient 进行查询（备用方案）
     */
    private Map<String, Object> queryWithChatClient(String question, long startTime) {
        try {
            // 检查 MCP 是否已初始化
            if (!mcpInitialized) {
                log.info("[数据查询] MCP Server 尚未预热，首次查询可能需要较长时间...");
            }
            
            // 1. 先查询数据库表结构
            String tableSchema = getDatabaseSchema();
            log.info("[数据查询] 获取到的表结构:\n{}", tableSchema);
            
            // 2. 构建系统提示词，包含动态表结构和输出格式要求
            String systemPrompt = """
                    你是一个专业的数据库查询助手，**必须使用可用的工具来执行查询**。
                    
                    **重要规则：**
                    1. **必须调用工具**：不要自己编造数据，必须使用提供的工具获取真实数据
                    2. **优先使用具体工具**：
                       - 查询用户总数 → 调用 getUserCount 工具
                       - 查询活跃用户 → 调用 getActiveUserCount 工具
                       - 查询聊天消息 → 调用 getChatMessageCount 工具
                       - 获取用户列表 → 调用 getUserList 工具
                       - 自定义 SQL → 调用 executeQuery 工具
                       - 查看表结构 → 调用 getTableSchema 工具
                    3. **基于工具返回结果回答**：分析工具返回的真实数据，用自然语言总结
                    4. **禁止编造数据**：如果工具返回错误或无法执行，明确说明
                    
                    **输出要求：**
                    - 简洁明了，控制在100字以内
                    - 直接给出答案，不要提及技术细节
                    - 如果有具体数字，明确说明（例如：“共有 N 个用户”）
                    - 如果结果为空，说明“未找到相关数据”
                    
                    **可用工具：**
                    - getUserCount: 查询用户总数
                    - getActiveUserCount: 查询活跃用户数
                    - getChatMessageCount: 查询聊天消息总数
                    - getUserList: 获取分页用户列表
                    - executeQuery: 执行自定义 SQL 查询（只读）
                    - getTableSchema: 获取数据库表结构
                    
                    **工作流程：**
                    1. 理解用户问题
                    2. **选择合适的工具并调用**
                    3. 分析工具返回的结果
                    4. 用自然语言回答用户
                    
                    请严格遵守以上规则，**必须调用工具获取真实数据**！""";
            
            // 3. 执行查询
            log.info("[数据查询] 开始调用 MCP，问题: {}", question);
            log.info("[数据查询] MCP 启用状态: enabled={}, initialized={}", mcpEnabled, mcpInitialized);
            
            // ⭐ 关键修复：显式传递 toolCallbacks 以启用自动工具执行循环
            var promptBuilder = mcpChatClient.prompt()
                .system(systemPrompt)
                .user(question);
            
            // 如果有 SyncMcpToolCallbackProvider，显式传递工具
            if (syncMcpToolCallbackProvider != null) {
                promptBuilder = promptBuilder.tools(syncMcpToolCallbackProvider.getToolCallbacks());
                log.info("[数据查询] 已注册 {} 个工具", syncMcpToolCallbackProvider.getToolCallbacks().length);
            }
            
            String answer = promptBuilder.call().content();
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("[数据查询] MCP 返回结果 (耗时: {} ms): {}", duration, answer);
            log.info("[数据查询] 回答长度: {} 字符", answer != null ? answer.length() : 0);
            
            // 4. 验证 MCP 返回的有效性
            if (isInvalidResponse(answer)) {
                log.warn("[数据查询] ⚠️ MCP 返回无效响应，触发降级机制");
                return fallbackToMyBatis(question, "MCP 返回无效结果");
            }
            
            // 5. 后处理：清理和格式化回答
            String formattedAnswer = formatAnswer(answer);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("question", question);
            result.put("answer", formattedAnswer);
            result.put("method", "MCP");
            result.put("duration_ms", duration);
            
            return result;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[数据查询] ❌ MCP 查询失败 (耗时: {} ms): {}", duration, e.getMessage(), e);
            
            // ⭐ 自动降级到 MyBatis
            return fallbackToMyBatis(question, e.getMessage());
        }
    }
    
    /**
     * 验证 MCP 返回是否有效
     */
    private boolean isInvalidResponse(String answer) {
        if (answer == null || answer.trim().isEmpty()) {
            return true;
        }
        
        String lower = answer.toLowerCase();
        
        // 检测常见的无效响应模式
        return lower.contains("执行以下 sql") ||
               lower.contains("执行sql") ||
               lower.equals("执行查询：") ||
               answer.trim().matches("^```sql.*```$") ||  // 只返回了 SQL 代码块
               answer.trim().length() < 10;  // 回答太短，可能是无效的
    }
    
    /**
     * 降级策略：当 MCP 失败时，使用实际的 MyBatis / JDBC 查询
     */
    private Map<String, Object> fallbackToMyBatis(String question, String reason) {
        log.info("[数据查询] 🔄 降级到 MyBatis 模式，原因: {}", reason);

        try {
            String answer = executeRealQuery(question);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("question", question);
            result.put("answer", answer);
            result.put("method", "MyBatis-Fallback");
            result.put("fallback_reason", reason);

            return result;

        } catch (Exception ex) {
            log.error("[数据查询] ❌ 降级查询也失败: {}", ex.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "查询服务暂时不可用");
            error.put("details", reason);
            return error;
        }
    }

    /**
     * 根据用户问题执行真实的数据库查询
     */
    private String executeRealQuery(String question) {
        String lower = question.toLowerCase();

        // 1. 用户数量查询
        if ((lower.contains("用户") || lower.contains("user"))
                && (lower.contains("多少") || lower.contains("数量") || lower.contains("总数") || lower.contains("count"))) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users", Long.class);
            return "当前系统中共有 " + (count != null ? count : 0) + " 个注册用户。";
        }

        // 2. 反馈数量查询
        if (lower.contains("消息") || lower.contains("聊天") || lower.contains("message")) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM conversation_feedback", Long.class);
            return "当前系统中共有 " + (count != null ? count : 0) + " 条用户反馈。";
        }

        // 3. 会话数量查询
        if (lower.contains("会话") || lower.contains("session") || lower.contains("对话")) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT user_id) FROM conversation_feedback", Long.class);
            return "当前系统中共有 " + (count != null ? count : 0) + " 条用户反馈。";
        }

        // 4. 路由调用日志数量
        if (lower.contains("路由") || lower.contains("调用") || lower.contains("routing")) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM routing_call_log", Long.class);
            return "当前系统中共有 " + (count != null ? count : 0) + " 条路由调用记录。";
        }

        // 默认：无法识别的查询
        return """
                抱歉，暂时无法处理该查询。目前支持以下查询：
                • 查询注册用户数量（如"有多少用户"）
                • 查询聊天消息数量（如"有多少条消息"）
                • 查询会话数量（如"有多少个会话"）
                • 查询路由调用记录数量（如"有多少条路由日志"）""";
    }
    
    /**
     * 格式化 MCP 返回的答案，使其更易理解
     */
    private String formatAnswer(String answer) {
        if (answer == null || answer.trim().isEmpty()) {
            return "未获取到查询结果";
        }
        
        // 移除可能的 Markdown 代码块标记
        String cleaned = answer.replaceAll("```sql.*?```", "")
                               .replaceAll("```.*?```", "")
                               .trim();
        
        // 移除多余的空行
        cleaned = cleaned.replaceAll("\n *\n", "\n");
        
        return cleaned;
    }
    
    /**
     * 获取数据库表结构信息
     * 通过 MCP 查询 PostgreSQL 的系统表，获取所有表的元数据
     */
    private String getDatabaseSchema() {
        try {
            // 查询所有表的基本信息
            String schemaQuery = """
                    SELECT
                        table_name,
                        string_agg(column_name || ' (' || data_type || ')', ', ' ORDER BY ordinal_position) as columns
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                    GROUP BY table_name
                    ORDER BY table_name;""";

            return mcpChatClient.prompt()
                .system("你是一个数据库助手。请执行以下 SQL 查询并返回结果：")
                .user("执行SQL: " + schemaQuery)
                .call()
                .content();
            
        } catch (Exception e) {
            // 如果获取失败，返回基本的表名列表
            return "可用表：users, conversation_feedback, routing_call_logs（详细结构获取失败）";
        }
    }

}
