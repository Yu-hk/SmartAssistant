package com.example.smartassistant.consumer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 表访问白名单配置（支持热重载）
 * 需要引入 spring-cloud-starter-bootstrap 依赖
 */
@Slf4j
@Component
@RefreshScope  // ⭐ 支持运行时刷新配置
@ConfigurationProperties(prefix = "mcp")
public class RefreshableMcpTableWhitelistConfig {
    
    private final ServiceConfig travel = new ServiceConfig();
    private final ServiceConfig consumer = new ServiceConfig();
    private final ServiceConfig food = new ServiceConfig();
    
    @lombok.Data
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
