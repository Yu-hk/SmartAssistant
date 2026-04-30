package com.example.smartassistant.consumer.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 权限控制测试
 * 验证数据库专用用户的权限隔离
 */
@SpringBootTest
public class McpPermissionTest {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 测试1: consumer_mcp 用户可以访问 users 表
     */
    @Test
    public void testConsumerMcpCanAccessUsers() {
        // 使用 consumer_mcp 用户查询
        String sql = "SELECT COUNT(*) FROM users";
        
        assertDoesNotThrow(() -> {
            Long count = jdbcTemplate.queryForObject(sql, Long.class);
            assertNotNull(count);
            assertTrue(count >= 0);
            System.out.println("✅ consumer_mcp 可以访问 users 表，记录数: " + count);
        });
    }
    
    /**
     * 测试2: consumer_mcp 用户可以访问 chat_messages_partitioned 表
     */
    @Test
    public void testConsumerMcpCanAccessChatMessages() {
        String sql = "SELECT COUNT(*) FROM chat_messages_partitioned";
        
        assertDoesNotThrow(() -> {
            Long count = jdbcTemplate.queryForObject(sql, Long.class);
            assertNotNull(count);
            System.out.println("✅ consumer_mcp 可以访问 chat_messages_partitioned 表，记录数: " + count);
        });
    }
    
    /**
     * 测试3: consumer_mcp 用户不能访问 tourist_attractions 表
     */
    @Test
    public void testConsumerMcpCannotAccessTouristAttractions() {
        String sql = "SELECT COUNT(*) FROM tourist_attractions";
        
        Exception exception = assertThrows(Exception.class, () -> {
            jdbcTemplate.queryForObject(sql, Long.class);
        });
        
        assertTrue(exception.getMessage().contains("permission denied") || 
                   exception.getMessage().contains("权限不够"));
        System.out.println("✅ consumer_mcp 无法访问 tourist_attractions 表（预期行为）");
    }
    
    /**
     * 测试4: consumer_mcp 用户不能执行写操作
     */
    @Test
    public void testConsumerMcpCannotWrite() {
        String sql = "INSERT INTO users (username, email) VALUES ('test', 'test@test.com')";
        
        Exception exception = assertThrows(Exception.class, () -> {
            jdbcTemplate.execute(sql);
        });
        
        assertTrue(exception.getMessage().contains("permission denied") || 
                   exception.getMessage().contains("只读事务"));
        System.out.println("✅ consumer_mcp 无法执行写操作（预期行为）");
    }
}
