package com.example.smartassistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 表访问白名单配置 - Travel Service
 * ⭐ 支持热重载（@RefreshScope）
 */
@Data
@Component
@RefreshScope  // ⭐ 支持运行时刷新配置
@ConfigurationProperties(prefix = "mcp")
public class McpTableWhitelistConfig {
    
    private ServiceConfig travel = new ServiceConfig();
    private ServiceConfig consumer = new ServiceConfig();
    private ServiceConfig food = new ServiceConfig();
    
    @Data
    public static class ServiceConfig {
        private List<String> allowedTables;
        private List<String> forbiddenTables;
    }
    
    /**
     * 检查表是否在白名单中
     */
    public boolean isTableAllowed(String serviceName, String tableName) {
        ServiceConfig config = getServiceConfig(serviceName);
        if (config == null || config.getAllowedTables() == null) {
            return false;
        }
        
        String lowerTableName = tableName.toLowerCase();
        return config.getAllowedTables().stream()
                .anyMatch(table -> table.equalsIgnoreCase(lowerTableName));
    }
    
    /**
     * 获取服务的白名单列表
     */
    public List<String> getAllowedTables(String serviceName) {
        ServiceConfig config = getServiceConfig(serviceName);
        return config != null ? config.getAllowedTables() : List.of();
    }
    
    /**
     * 获取服务的黑名单列表
     */
    public List<String> getForbiddenTables(String serviceName) {
        ServiceConfig config = getServiceConfig(serviceName);
        return config != null ? config.getForbiddenTables() : List.of();
    }
    
    private ServiceConfig getServiceConfig(String serviceName) {
        return switch (serviceName.toLowerCase()) {
            case "travel" -> travel;
            case "consumer" -> consumer;
            case "food" -> food;
            default -> null;
        };
    }
}
