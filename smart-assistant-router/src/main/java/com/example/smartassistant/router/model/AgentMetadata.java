/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 特征元数据
 * 从 Nacos 注册信息中读取，用于智能路由决策
 */
@Data
@NoArgsConstructor
public class AgentMetadata {
    
    /**
     * Agent 类型标识
     * 例如：product_agent, order_agent
     */
    private String agentType;
    
    /**
     * 能力列表（逗号分隔）
     * 例如：cuisine_query,restaurant_recommendation,nearby_search
     */
    private String capabilities;
    
    /**
     * 关键词列表（逗号分隔）
     * 用于快速匹配用户意图
     * 例如：美食,餐厅,菜系,特色菜,吃什么
     */
    private String keywords;
    
    /**
     * 支持的菜系类型（仅美食 Agent）
     * 例如：川菜,粤菜,鲁菜,火锅,烧烤
     */
    private String cuisineTypes;
    
    /**
     * 是否支持位置查询
     */
    private Boolean supportLocation = false;
    
    /**
     * 是否支持天气查询
     */
    private Boolean supportWeather = false;
    
    /**
     * 是否支持出行规划
     */
    private Boolean supportPlanning = false;
    
    /**
     * 优先级（数值越大优先级越高）
     */
    private Integer priority = 0;
    
    // ⭐ 版本管理字段
    /**
     * Agent 版本号 (语义化版本: MAJOR.MINOR.PATCH)
     * 例如: 1.0.0, 1.1.0, 2.0.0
     */
    private String version = "1.0.0";
    
    /**
     * A2A 协议版本
     * 例如: v1, v2
     */
    private String protocolVersion = "v1";
    
    /**
     * 最低客户端版本要求
     * 低于此版本的客户端将无法调用此 Agent
     */
    private String minClientVersion = "1.0.0";
    
    /**
     * 回复缓存 TTL（秒）
     * 由各 Agent 在 metadata 中声明，Router 直接读取。
     * 为 null 时使用 Router 默认值（3600s）。
     */
    private Long cacheTtlSeconds;

    /**
     * ⭐ 是否始终缓存回复（忽略低频问题检查）
     * <p>
     * 设置为 true 时，Agent 的回复将在首次命中时即被缓存，
     * 无需等待成为高频问题（≥2 次）。
     * 适用于 Product（商品价格/库存）和 General（天气/新闻）等
     * 具有稳定、可复用回复模式的场景。
     */
    private Boolean alwaysCacheReply = false;

    /**
     * 支持的协议列表 (逗号分隔)
     * 例如: a2a-v1,a2a-v2
     */
    private String supportedProtocols = "a2a-v1";
    
    /**
     * 解析能力列表
     */
    public String[] getCapabilitiesArray() {
        if (capabilities == null || capabilities.isEmpty()) {
            return new String[0];
        }
        return capabilities.split(",");
    }
    
    /**
     * 解析关键词列表
     */
    public String[] getKeywordsArray() {
        if (keywords == null || keywords.isEmpty()) {
            return new String[0];
        }
        return keywords.split(",");
    }
    
    /**
     * 检查是否具有某个能力
     */
    public boolean hasCapability(String capability) {
        if (capabilities == null) {
            return false;
        }
        return capabilities.contains(capability);
    }
    
    /**
     * 检查关键词是否匹配
     */
    public boolean matchesKeyword(String keyword) {
        if (keywords == null) {
            return false;
        }
        return keywords.contains(keyword);
    }
    
    // ⭐ 版本相关方法
    
    /**
     * 解析支持的协议列表
     */
    public String[] getSupportedProtocolsArray() {
        if (supportedProtocols == null || supportedProtocols.isEmpty()) {
            return new String[0];
        }
        return supportedProtocols.split(",");
    }
    
    /**
     * 检查是否支持指定协议
     */
    public boolean supportsProtocol(String protocol) {
        if (supportedProtocols == null) {
            return false;
        }
        return supportedProtocols.contains(protocol);
    }
    
    /**
     * 检查客户端版本是否兼容
     */
    public boolean isClientVersionCompatible(String clientVersion) {
        if (minClientVersion == null || clientVersion == null) {
            return true; // 未配置则默认兼容
        }
        return compareVersions(clientVersion, minClientVersion) >= 0;
    }
    
    /**
     * 比较版本号 (语义化版本比较)
     * @return >0 如果 v1 > v2, =0 如果相等, <0 如果 v1 < v2
     */
    private int compareVersions(String v1, String v2) {
        try {
            String[] parts1 = v1.split("\\.");
            String[] parts2 = v2.split("\\.");
            
            int length = Math.max(parts1.length, parts2.length);
            for (int i = 0; i < length; i++) {
                int num1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
                int num2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
                
                if (num1 != num2) {
                    return num1 - num2;
                }
            }
            return 0;
        } catch (NumberFormatException e) {
            // 如果解析失败，按字符串比较
            return v1.compareTo(v2);
        }
    }
}
