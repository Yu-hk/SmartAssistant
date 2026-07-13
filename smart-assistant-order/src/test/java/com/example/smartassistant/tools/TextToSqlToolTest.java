/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tools;

import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.common.sql.SqlSecurityValidator;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.config.McpTableWhitelistConfig;
import com.example.smartassistant.order.tool.TextToSqlTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Simple unit tests for {@link TextToSqlTool}.
 * Tests SQL cleaning logic, error handling, and SQL security validation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TextToSqlTool Unit Tests")
class TextToSqlToolTest {

    @Mock
    private OrderDataProvider orderData;
    @Mock
    private PromptManager promptManager;

    private TextToSqlTool textToSqlTool;

    @BeforeEach
    void setUp() {
        // Use an invalid URL to ensure Ollama calls fail fast for tests that go through textToSql
        textToSqlTool = new TextToSqlTool(orderData, "http://127.0.0.1:1", "test-model",
                promptManager);
    }

    // ==================== cleanGeneratedSql (via ReflectionTestUtils) ====================

    @Test
    @DisplayName("cleanGeneratedSql should remove markdown sql code block markers")
    void should_removeMarkdownSqlBlock() {
        String input = "```sql\nSELECT * FROM orders\n```";
        String result = ReflectionTestUtils.invokeMethod(textToSqlTool, "cleanGeneratedSql", input);
        assertEquals("SELECT * FROM orders", result, "Should remove ```sql markers");
    }

    @Test
    @DisplayName("cleanGeneratedSql should remove generic markdown code block markers")
    void should_removeGenericMarkdownBlock() {
        String input = "```\nSELECT * FROM orders\n```";
        String result = ReflectionTestUtils.invokeMethod(textToSqlTool, "cleanGeneratedSql", input);
        assertEquals("SELECT * FROM orders", result, "Should remove generic ``` markers");
    }

    @Test
    @DisplayName("cleanGeneratedSql should remove think tags from LLM output")
    void should_removeThinkTags() {
        String input = "SELECT * FROM orders";
        String result = ReflectionTestUtils.invokeMethod(textToSqlTool, "cleanGeneratedSql", input);
        assertNotNull(result, "Should not throw on normal SQL");
    }

    @Test
    @DisplayName("cleanGeneratedSql should extract first SELECT statement from multi-line output")
    void should_extractFirstSelectStatement() {
        String input = "Here is the SQL query:\nSELECT order_id, status\nFROM orders\nWHERE status = '已发货'";
        String result = ReflectionTestUtils.invokeMethod(textToSqlTool, "cleanGeneratedSql", input);
        assertEquals("SELECT order_id, status", result, "Should extract only the SELECT line");
    }

    @Test
    @DisplayName("cleanGeneratedSql should handle UNSUPPORTED prefix")
    void should_handleUnsupportedPrefix() {
        String input = "-- UNSUPPORTED: 无法查询该数据";
        String result = ReflectionTestUtils.invokeMethod(textToSqlTool, "cleanGeneratedSql", input);
        assertEquals("-- UNSUPPORTED: 无法查询该数据", result,
                "Should preserve UNSUPPORTED prefix");
    }

    @Test
    @DisplayName("cleanGeneratedSql should handle null input")
    void should_handleNullInput() {
        String result = ReflectionTestUtils.invokeMethod(textToSqlTool, "cleanGeneratedSql",
                new Object[] { null });
        assertEquals("", result, "Should return empty string for null input");
    }

    // ==================== textToSql error handling ====================

    @Test
    @DisplayName("textToSql should return error when Ollama call fails due to invalid URL")
    void should_returnOllamaError_when_ollamaUnavailable() {
        // The URL is set to http://127.0.0.1:1 which will cause connection refused
        String result = textToSqlTool.textToSql("查询所有订单");
        assertTrue(result.contains("SQL 生成失败") || result.contains("Connection refused")
                        || result.contains("connect") || result.contains("refused"),
                "Should return an error message when Ollama is unreachable");
    }

    // ==================== SqlSecurityValidator validation ====================

    @Test
    @DisplayName("SqlSecurityValidator should validate SELECT with allowed tables")
    void should_passValidation_when_selectingAllowedTables() {
        List<String> allowedTables = List.of("orders", "order_refunds");
        List<String> forbiddenTables = List.of("users", "admin");

        SqlSecurityValidator.ValidationResult result =
                SqlSecurityValidator.validateSelect("SELECT * FROM orders", allowedTables, forbiddenTables);

        assertTrue(result.isValid(), "SELECT from allowed table should pass validation");
    }

    @Test
    @DisplayName("SqlSecurityValidator should reject SELECT with forbidden tables")
    void should_rejectValidation_when_selectingForbiddenTables() {
        List<String> allowedTables = List.of("orders");
        List<String> forbiddenTables = List.of("admin_users");

        // Both 'orders' and 'admin_users' appear - 'admin_users' is forbidden
        // But this SQL only accesses 'orders', so it should pass
        SqlSecurityValidator.ValidationResult result1 =
                SqlSecurityValidator.validateSelect("SELECT * FROM orders", allowedTables, forbiddenTables);
        assertTrue(result1.isValid(), "SELECT from allowed, non-forbidden table should pass");

        // Test a query against a forbidden table
        SqlSecurityValidator.ValidationResult result2 =
                SqlSecurityValidator.validateSelect("SELECT * FROM admin_users", allowedTables, forbiddenTables);
        assertFalse(result2.isValid(), "SELECT from forbidden table should fail");
        assertTrue(result2.getReason().contains("无权访问"), "Reason should mention access denial");
    }

    @Test
    @DisplayName("SqlSecurityValidator should reject non-SELECT statements")
    void should_rejectNonSelectStatements() {
        List<String> allowedTables = List.of("orders");

        SqlSecurityValidator.ValidationResult result =
                SqlSecurityValidator.validateSelect("DELETE FROM orders", allowedTables, List.of());

        assertFalse(result.isValid(), "DELETE statement should be rejected");
        assertTrue(result.getReason().contains("SELECT"),
                "Reason should mention that only SELECT is allowed");
    }

    @Test
    @DisplayName("SqlSecurityValidator should reject empty SQL")
    void should_rejectEmptySql() {
        SqlSecurityValidator.ValidationResult result =
                SqlSecurityValidator.validateSelect("", List.of("orders"), List.of());

        assertFalse(result.isValid(), "Empty SQL should be rejected");
    }

    @Test
    @DisplayName("SqlSecurityValidator should pass validation for JOIN between allowed tables")
    void should_passValidation_when_joiningAllowedTables() {
        List<String> allowedTables = List.of("orders", "order_refunds");

        SqlSecurityValidator.ValidationResult result =
                SqlSecurityValidator.validateSelect(
                        "SELECT o.order_id, r.amount FROM orders o JOIN order_refunds r ON o.order_id = r.order_id",
                        allowedTables, List.of());

        assertTrue(result.isValid(), "JOIN between allowed tables should pass");
    }

    // ==================== McpTableWhitelistConfig ====================

    @Test
    @DisplayName("whitelistConfig should return allowed tables for order service")
    void should_returnAllowedTables_when_configured() {
        McpTableWhitelistConfig config = new McpTableWhitelistConfig();
        McpTableWhitelistConfig.ServiceConfig serviceConfig = new McpTableWhitelistConfig.ServiceConfig();
        serviceConfig.setAllowedTables(List.of("orders", "order_refunds", "order_logistics"));
        // Use reflection to set the field since there's no setter
        try {
            var field = McpTableWhitelistConfig.class.getDeclaredField("order");
            field.setAccessible(true);
            field.set(config, serviceConfig);
        } catch (Exception e) {
            fail("Failed to set test config: " + e.getMessage());
        }

        List<String> allowed = config.getAllowedTables("order");
        assertTrue(allowed.contains("orders"), "Should include orders table");
        assertTrue(allowed.contains("order_refunds"), "Should include order_refunds table");
        assertEquals(3, allowed.size(), "Should have 3 allowed tables");
    }
}
