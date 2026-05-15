/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.agent;

import com.example.smartassistant.config.McpTableWhitelistConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MCP Agent 白名单验证测试
 * 确保应用层表访问控制正常工作
 */
@DisplayName("MCP Agent 白名单验证测试")
class McpAgentServiceWhitelistTest {

    private JdbcTemplate jdbcTemplate;
    private McpTableWhitelistConfig whitelistConfig;
    private McpAgentService.DatabaseQueryTools queryTools;

    @BeforeEach
    void setUp() {
        // 创建 Mock 对象
        jdbcTemplate = mock(JdbcTemplate.class);
        whitelistConfig = new McpTableWhitelistConfig();
        
        // 配置 Travel 服务的白名单
        McpTableWhitelistConfig.ServiceConfig travelConfig = new McpTableWhitelistConfig.ServiceConfig();
        travelConfig.setAllowedTables(List.of(
            "tourist_attractions",
            "attraction_highlights",
            "attraction_tags"
        ));
        travelConfig.setForbiddenTables(List.of(
            "users",
            "chat_messages",
            "user_profiles"
        ));
        whitelistConfig.setTravel(travelConfig);
        
        // 创建查询工具
        queryTools = new McpAgentService.DatabaseQueryTools(jdbcTemplate, whitelistConfig);
    }

    @Test
    @DisplayName("测试允许访问白名单中的表")
    void testAllowedTableAccess() {
        String sql = "SELECT * FROM tourist_attractions LIMIT 10";
        
        // Mock 返回结果
        when(jdbcTemplate.queryForList(sql)).thenReturn(List.of(Map.of("name", "故宫")));
        
        // 应该成功执行
        assertDoesNotThrow(() -> {
            List<Map<String, Object>> result = queryTools.executeQuery(sql);
            assertNotNull(result);
            assertEquals(1, result.size());
        });
    }

    @Test
    @DisplayName("测试拒绝访问黑名单中的表")
    void testForbiddenTableAccess() {
        String sql = "SELECT * FROM users WHERE id = 1";
        
        // 应该抛出 SecurityException
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            queryTools.executeQuery(sql);
        });
        
        assertTrue(exception.getMessage().contains("无权访问表"));
        assertTrue(exception.getMessage().contains("users"));
    }

    @Test
    @DisplayName("测试拒绝访问不在白名单中的表")
    void testTableNotInWhitelist() {
        String sql = "SELECT * FROM some_other_table";
        
        // 应该抛出 SecurityException
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            queryTools.executeQuery(sql);
        });
        
        assertTrue(exception.getMessage().contains("无权访问表"));
        assertTrue(exception.getMessage().contains("不在白名单中"));
    }

    @Test
    @DisplayName("测试多表 JOIN 查询 - 所有表都在白名单中")
    void testMultiTableJoin_AllAllowed() {
        String sql = """
            SELECT a.name, h.content 
            FROM tourist_attractions a 
            JOIN attraction_highlights h ON a.id = h.attraction_id
            LIMIT 5
            """;
        
        when(jdbcTemplate.queryForList(sql)).thenReturn(List.of());
        
        // 应该成功执行
        assertDoesNotThrow(() -> {
            queryTools.executeQuery(sql);
        });
    }

    @Test
    @DisplayName("测试多表 JOIN 查询 - 包含黑名单表")
    void testMultiTableJoin_ContainsForbidden() {
        String sql = """
            SELECT a.name, u.username 
            FROM tourist_attractions a 
            JOIN users u ON a.created_by = u.id
            """;
        
        // 应该抛出 SecurityException
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            queryTools.executeQuery(sql);
        });
        
        assertTrue(exception.getMessage().contains("无权访问表"));
    }

    @Test
    @DisplayName("测试拒绝非 SELECT 语句")
    void testRejectNonSelectStatements() {
        String[] dangerousSqls = {
            "DELETE FROM tourist_attractions WHERE id = 1",
            "UPDATE tourist_attractions SET name = 'test'",
            "INSERT INTO tourist_attractions (name) VALUES ('test')",
            "DROP TABLE tourist_attractions",
            "ALTER TABLE tourist_attractions ADD COLUMN test VARCHAR(100)"
        };
        
        for (String sql : dangerousSqls) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
                queryTools.executeQuery(sql);
            });
            
            assertTrue(
                exception.getMessage().contains("只允许执行 SELECT") || 
                exception.getMessage().contains("禁止执行危险操作")
            );
        }
    }

    @Test
    @DisplayName("测试 information_schema 查询（系统表应被排除）")
    void testInformationSchemaQuery() {
        String sql = """
            SELECT table_name, column_name 
            FROM information_schema.columns 
            WHERE table_schema = 'public'
            """;
        
        when(jdbcTemplate.queryForList(sql)).thenReturn(List.of());
        
        // 应该成功执行（information_schema 是系统表，不应被白名单限制）
        assertDoesNotThrow(() -> {
            queryTools.executeQuery(sql);
        });
    }
}
