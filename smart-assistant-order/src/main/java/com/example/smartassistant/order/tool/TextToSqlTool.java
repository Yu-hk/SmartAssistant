/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 * Licensed under the MIT License.
 */
package com.example.smartassistant.order.tool;

import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.common.sql.SqlSecurityValidator;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Converts natural-language order questions into read-only SQL queries. */
@Component
public class TextToSqlTool {
    private static final Logger log = LoggerFactory.getLogger(TextToSqlTool.class);

    private final PromptManager promptManager;
    private final OrderDataProvider orderData;
    private final ChatModel chatModel;

    public TextToSqlTool(OrderDataProvider orderData,
                         @Qualifier("deepSeekChatModel") ChatModel chatModel,
                         PromptManager promptManager) {
        this.orderData = orderData;
        this.chatModel = chatModel;
        this.promptManager = promptManager;
    }

    @Tool(description = "文本转 SQL 查询工具。将订单、退款、物流和商品相关的自然语言问题转换为只读 SQL，并返回查询结果。")
    public String textToSql(@ToolParam(description = "需要查询的订单数据问题", required = true) String question) {
        log.info("[TextToSqlTool] 收到自然语言查询: {}", question);
        String generatedSql;
        try {
            generatedSql = callModelForSql(question);
        } catch (Exception e) {
            log.error("[TextToSqlTool] LLM 生成 SQL 失败: {}", e.getMessage());
            return "SQL 生成失败：" + e.getMessage();
        }
        if (generatedSql == null || generatedSql.isBlank()) {
            return "LLM 未能生成有效的 SQL 语句，请换一种方式描述问题。";
        }

        generatedSql = cleanGeneratedSql(generatedSql);
        if (generatedSql.startsWith("-- UNSUPPORTED:") || generatedSql.startsWith("--UNSUPPORTED:")) {
            return "无法将问题转换为数据查询："
                    + generatedSql.replaceAll("^--\\s*UNSUPPORTED:\\s*", "");
        }

        log.info("[TextToSqlTool] 清理后的 SQL: {}", generatedSql);
        List<String> allowedTables = orderData.getAllowedTables();
        SqlSecurityValidator.ValidationResult validation =
                SqlSecurityValidator.validateSelect(generatedSql, allowedTables, List.of());
        if (!validation.isValid()) {
            log.warn("[TextToSqlTool] SQL 安全校验未通过: {}", validation.getReason());
            return "SQL 安全校验失败：" + validation.getReason()
                    + "\n仅支持查询以下表：" + allowedTables;
        }

        try {
            List<Map<String, Object>> queryResult = orderData.executeSelectSql(generatedSql);
            if (queryResult.isEmpty()) {
                return "查询结果：没有找到匹配的数据。";
            }
            StringBuilder response = new StringBuilder("查询结果（共 ")
                    .append(queryResult.size()).append(" 行）：\n\n");
            for (int index = 0; index < queryResult.size(); index++) {
                response.append("--- 结果 ").append(index + 1).append(" ---\n");
                for (Map.Entry<String, Object> entry : queryResult.get(index).entrySet()) {
                    response.append(entry.getKey()).append("：")
                            .append(entry.getValue() == null ? "null" : entry.getValue())
                            .append('\n');
                }
                response.append('\n');
            }
            return response.toString();
        } catch (Exception e) {
            log.error("[TextToSqlTool] SQL 执行失败: {}", e.getMessage());
            return "SQL 执行失败：" + e.getMessage() + "\n\n生成的 SQL：" + generatedSql;
        }
    }

    private String callModelForSql(String question) {
        String prompt = promptManager.textToSql() + "\n\nTable schema:\n"
                + "orders: order_id(VARCHAR), user_id(BIGINT), product_name(VARCHAR), amount(DECIMAL), status(VARCHAR), carrier(VARCHAR), tracking_no(VARCHAR), product_type(VARCHAR), delivered_date(TIMESTAMP), created_at(TIMESTAMP)\n"
                + "order_refunds: order_id(VARCHAR), reason(TEXT), amount(DECIMAL), status(VARCHAR), created_at(TIMESTAMP)\n"
                + "order_logistics: tracking_no(VARCHAR), order_id(VARCHAR), carrier(VARCHAR), status(VARCHAR), trajectory(JSONB), created_at(TIMESTAMP), updated_at(TIMESTAMP)\n"
                + "approval_records: order_id(VARCHAR), action_type(VARCHAR), reason(TEXT), status(VARCHAR), created_at(TIMESTAMP), confirmed_at(TIMESTAMP), consumed_at(TIMESTAMP)\n"
                + "products: product_code(VARCHAR), product_name(VARCHAR), price(DECIMAL), stock(VARCHAR), spec(TEXT), colors(VARCHAR), created_at(TIMESTAMP)\n\n"
                + "User question: " + question;
        return chatModel.call(prompt);
    }

    private String cleanGeneratedSql(String sql) {
        if (sql == null) {
            return "";
        }
        String cleaned = sql.trim();
        if (cleaned.startsWith("```sql")) {
            cleaned = cleaned.substring(6).trim();
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3).trim();
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }
        cleaned = cleaned.replaceAll("(?s)<think>.*?</think>", "").trim();
        for (String line : cleaned.split("\\R")) {
            String candidate = line.trim();
            if (candidate.toUpperCase().startsWith("SELECT")
                    || candidate.startsWith("-- UNSUPPORTED")
                    || candidate.startsWith("--UNSUPPORTED")) {
                return candidate;
            }
        }
        return cleaned;
    }
}
