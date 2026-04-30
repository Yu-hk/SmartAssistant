package com.example.smartassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 权限控制测试 - Travel 服务
 * 验证数据库专用用户的权限隔离
 */
@SpringBootTest
public class McpPermissionTest {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 测试1: travel_mcp 用户可以访问 tourist_attractions 表
     */
    @Test
    public void testTravelMcpCanAccessTouristAttractions() {
        String sql = "SELECT COUNT(*) FROM tourist_attractions";
        
        assertDoesNotThrow(() -> {
            Long count = jdbcTemplate.queryForObject(sql, Long.class);
            assertNotNull(count);
            assertTrue(count >= 0);
            System.out.println("✅ travel_mcp 可以访问 tourist_attractions 表，记录数: " + count);
        });
    }
    
    /**
     * 测试2: travel_mcp 用户可以访问 attraction_highlights 表
     */
    @Test
    public void testTravelMcpCanAccessAttractionHighlights() {
        String sql = "SELECT COUNT(*) FROM attraction_highlights";
        
        assertDoesNotThrow(() -> {
            Long count = jdbcTemplate.queryForObject(sql, Long.class);
            assertNotNull(count);
            System.out.println("✅ travel_mcp 可以访问 attraction_highlights 表，记录数: " + count);
        });
    }
    
    /**
     * 测试3: travel_mcp 用户不能访问 users 表
     */
    @Test
    public void testTravelMcpCannotAccessUsers() {
        String sql = "SELECT COUNT(*) FROM users";
        
        Exception exception = assertThrows(Exception.class, () -> {
            jdbcTemplate.queryForObject(sql, Long.class);
        });
        
        assertTrue(exception.getMessage().contains("permission denied") || 
                   exception.getMessage().contains("权限不够"));
        System.out.println("✅ travel_mcp 无法访问 users 表（预期行为）");
    }
    
    /**
     * 测试4: travel_mcp 用户不能访问 chat_messages 表
     */
    @Test
    public void testTravelMcpCannotAccessChatMessages() {
        String sql = "SELECT COUNT(*) FROM chat_messages_partitioned";
        
        Exception exception = assertThrows(Exception.class, () -> {
            jdbcTemplate.queryForObject(sql, Long.class);
        });
        
        assertTrue(exception.getMessage().contains("permission denied") || 
                   exception.getMessage().contains("权限不够"));
        System.out.println("✅ travel_mcp 无法访问 chat_messages 表（预期行为）");
    }
    
    /**
     * 测试5: travel_mcp 用户不能执行写操作
     */
    @Test
    public void testTravelMcpCannotWrite() {
        String sql = "INSERT INTO tourist_attractions (name, city) VALUES ('Test', 'Test')";
        
        Exception exception = assertThrows(Exception.class, () -> {
            jdbcTemplate.execute(sql);
        });
        
        assertTrue(exception.getMessage().contains("permission denied") || 
                   exception.getMessage().contains("只读事务"));
        System.out.println("✅ travel_mcp 无法执行写操作（预期行为）");
    }
}
