/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tools;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.common.sql.SqlSecurityValidator;
import com.example.smartassistant.config.McpTableWhitelistConfig;
import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 文本转 SQL 工具（Text-to-SQL）
 * <p>
 * 将用户的自然语言问题自动转换为 SQL 查询，经过安全校验后执行并返回结构化结果。
 * 仅支持 SELECT 只读查询，且仅限 order 相关白名单表。
 * <p>
 * 注：直接调用本地 Ollama API 进行 SQL 生成，避免 Spring AI ChatModel 多 Bean 冲突。
 * </p>
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>接收用户的自然语言问题</li>
 *   <li>使用 Ollama 本地模型将问题转换为 SQL 查询语句</li>
 *   <li>使用 {@link SqlSecurityValidator} 进行 AST 级别的安全校验</li>
 *   <li>通过校验后，通过 {@link JdbcTemplate} 执行 SQL</li>
 *   <li>返回格式化的查询结果</li>
 * </ol>
 */
@Component
public class TextToSqlTool {

    private static final Logger log = LoggerFactory.getLogger(TextToSqlTool.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String SERVICE_NAME = "order";

    // P2 Prompt 外部化：prompts/order/text-to-sql.txt
    private final PromptManager promptManager;

    private final JdbcTemplate jdbcTemplate;
    private final McpTableWhitelistConfig whitelistConfig;
    private final String ollamaBaseUrl;
    private final String ollamaModel;
    private final ToolRegistry toolRegistry;

    public TextToSqlTool(JdbcTemplate jdbcTemplate,
                         McpTableWhitelistConfig whitelistConfig,
                         @Value("${spring.ai.ollama.base-url}") String ollamaBaseUrl,
                         @Value("${spring.ai.ollama.chat.options.model:deepseek-r1:7b}") String ollamaModel,
                         ToolRegistry toolRegistry,
                         PromptManager promptManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.whitelistConfig = whitelistConfig;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.ollamaModel = ollamaModel;
        this.toolRegistry = toolRegistry;
        this.promptManager = promptManager;
    }

    @PostConstruct
    public void initTools() {
        toolRegistry.register(new ToolDefinition("textToSql", "文本转SQL查询",
                ToolRiskLevel.MEDIUM, java.time.Duration.ofSeconds(30), true, 1, 5, new String[0]));
    }

    @Tool(description = "⭐ 文本转SQL查询工具。将用户用自然语言提出的数据问题，"
            + "自动转换为 SQL 查询并执行返回结果。"
            + "支持查询订单、退款、物流、商品等数据。"
            + "示例问题：'查询所有已发货的订单'、'统计每种状态的订单数量'、'查询退款金额最高的前3笔订单'")
    public String textToSql(
            @ToolParam(description = "用户用自然语言提出的数据查询问题，如'查询所有已发货的订单'", required = true) String question) {
        log.info("[TextToSqlTool] 收到自然语言查询: {}", question);

        // Step 1: 调用 Ollama 本地模型，将自然语言转换为 SQL
        String generatedSql;
        try {
            generatedSql = callOllamaForSql(question);
        } catch (Exception e) {
            log.error("[TextToSqlTool] LLM 生成 SQL 失败: {}", e.getMessage());
            return "❌ SQL 生成失败：" + e.getMessage();
        }

        if (generatedSql == null || generatedSql.isBlank()) {
            return "❌ LLM 未能生成有效的 SQL 语句，请换一种方式描述您的问题。";
        }

        // 清理输出（去除可能的 markdown 代码块标记和 思考链内容）
        generatedSql = cleanGeneratedSql(generatedSql);

        // 检查 LLM 是否标记为不支持
        if (generatedSql.startsWith("-- UNSUPPORTED:") || generatedSql.startsWith("--UNSUPPORTED:")) {
            return "❌ 无法将您的问题转换为数据库查询。" + generatedSql.replaceAll("^--\\s*UNSUPPORTED:\\s*", "");
        }

        log.info("[TextToSqlTool] 清理后的 SQL: {}", generatedSql);

        // Step 2: 安全校验（AST 级别表名白名单）
        List<String> allowedTables = whitelistConfig.getAllowedTables(SERVICE_NAME);
        List<String> forbiddenTables = whitelistConfig.getForbiddenTables(SERVICE_NAME);

        SqlSecurityValidator.ValidationResult result =
                SqlSecurityValidator.validateSelect(generatedSql, allowedTables, forbiddenTables);

        if (!result.isValid()) {
            log.warn("[TextToSqlTool] ❌ 安全校验未通过: {}", result.getReason());
            log.warn("[TextToSqlTool]    SQL: {}", generatedSql);
            return "❌ 安全校验失败：" + result.getReason()
                    + "\n仅支持查询以下表：" + allowedTables;
        }

        log.info("[TextToSqlTool] ✅ 安全校验通过，允许的表: {}", allowedTables);

        // Step 3: 执行 SQL 查询
        try {
            List<Map<String, Object>> queryResult = jdbcTemplate.queryForList(generatedSql);
            log.info("[TextToSqlTool] ✅ 查询成功，返回 {} 行", queryResult.size());

            if (queryResult.isEmpty()) {
                return "📊 查询结果：没有找到匹配的数据。";
            }

            // 格式化结果
            StringBuilder sb = new StringBuilder();
            sb.append("📊 查询结果（共 ").append(queryResult.size()).append(" 行）：\n\n");

            for (int i = 0; i < queryResult.size(); i++) {
                sb.append("--- 结果 ").append(i + 1).append(" ---\n");
                Map<String, Object> row = queryResult.get(i);
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    sb.append(entry.getKey()).append("：").append(entry.getValue() != null ? entry.getValue() : "null").append("\n");
                }
                sb.append("\n");
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("[TextToSqlTool] ❌ SQL 执行失败: {}", e.getMessage());
            return "❌ SQL 执行失败：" + e.getMessage()
                    + "\n\n生成的 SQL：" + generatedSql;
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 调用 Ollama 本地 API 生成 SQL
     */
    private String callOllamaForSql(String question) throws Exception {
        URL url = URI.create(ollamaBaseUrl + "/api/generate").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        String requestBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                "model", ollamaModel,
                "prompt", promptManager.textToSql() + "\n\n表结构信息：\n"
                        + "orders: order_id(VARCHAR), user_id(BIGINT), product_name(VARCHAR), amount(DECIMAL), status(VARCHAR), carrier(VARCHAR), tracking_no(VARCHAR), product_type(VARCHAR), delivered_date(TIMESTAMP), created_at(TIMESTAMP)\n"
                        + "order_refunds: order_id(VARCHAR), reason(TEXT), amount(DECIMAL), status(VARCHAR), created_at(TIMESTAMP)\n"
                        + "order_logistics: tracking_no(VARCHAR), order_id(VARCHAR), carrier(VARCHAR), status(VARCHAR), trajectory(JSONB), created_at(TIMESTAMP), updated_at(TIMESTAMP)\n"
                        + "approval_records: order_id(VARCHAR), action_type(VARCHAR), reason(TEXT), status(VARCHAR), created_at(TIMESTAMP), confirmed_at(TIMESTAMP), consumed_at(TIMESTAMP)\n"
                        + "products: product_code(VARCHAR), product_name(VARCHAR), price(DECIMAL), stock(VARCHAR), spec(TEXT), colors(VARCHAR), created_at(TIMESTAMP)\n\n用户问题：" + question,
                "stream", false,
                "options", Map.of("temperature", 0.1) // 低温度让 SQL 生成更精确
        ));

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String errorBody = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("Ollama API 返回错误: HTTP " + responseCode + ", " + errorBody);
        }

        String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);
        return root.has("response") ? root.get("response").asText() : "";
    }

    /**
     * 清理 LLM 生成的 SQL，去除 标记和思考链
     */
    private String cleanGeneratedSql(String sql) {
        if (sql == null) return "";
        String cleaned = sql.trim();

        // 去除 ```sql ... ``` 和 ``` ... ``` 标记
        if (cleaned.startsWith("```sql")) {
            cleaned = cleaned.substring(6).trim();
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3).trim();
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }

        // 去除 思考链（deepseek-r1 输出中 标签之间的内容）
        int thinkStart = cleaned.indexOf("");
        if (thinkStart >= 0) {
            int thinkEnd = cleaned.indexOf("", thinkStart);
            if (thinkEnd >= 0) {
                cleaned = (cleaned.substring(0, thinkStart) + cleaned.substring(thinkEnd + 7)).trim();
            } else {
                // 没有闭合标签，去掉从 开始的所有内容
                cleaned = cleaned.substring(0, thinkStart).trim();
            }
        }

        // 只保留第一行有效的 SQL（以 SELECT 开头）
        String[] lines = cleaned.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase().startsWith("SELECT") || trimmed.startsWith("-- UNSUPPORTED") || trimmed.startsWith("--UNSUPPORTED")) {
                return trimmed;
            }
        }

        return cleaned;
    }
}
