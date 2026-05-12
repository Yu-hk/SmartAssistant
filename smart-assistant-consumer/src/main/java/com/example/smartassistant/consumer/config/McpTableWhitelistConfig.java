/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP 表访问白名单配置
 * 从 mcp-table-whitelist.yml 读取配置
 */
@Data
@Configuration
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
     * @param serviceName 服务名称 (travel/consumer/food)
     * @param tableName 表名
     * @return 是否允许访问
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
     * 检查表是否在黑名单中
     * @param serviceName 服务名称
     * @param tableName 表名
     * @return 是否禁止访问
     */
    public boolean isTableForbidden(String serviceName, String tableName) {
        ServiceConfig config = getServiceConfig(serviceName);
        if (config == null || config.getForbiddenTables() == null) {
            return false;
        }
        
        String lowerTableName = tableName.toLowerCase();
        return config.getForbiddenTables().stream()
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
