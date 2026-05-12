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
 * 已发现的 Agent 信息
 */
@Data
@NoArgsConstructor
public class DiscoveredAgent {
    
    /**
     * 服务名称（Nacos 中的服务名）
     */
    private String serviceName;
    
    /**
     * Agent 名称
     */
    private String agentName;
    
    /**
     * IP 地址
     */
    private String ip;
    
    /**
     * 端口
     */
    private Integer port;
    
    /**
     * 完整 URL
     */
    private String url;
    
    /**
     * 特征元数据
     */
    private AgentMetadata metadata;
    
    /**
     * 是否健康
     */
    private Boolean healthy = false;
    
    /**
     * 权重
     */
    private Double weight = 1.0;
    
    /**
     * 最后发现时间
     */
    private Long lastDiscoveredAt = System.currentTimeMillis();
    
    // ⭐ Agent 热插拔相关字段
    /**
     * 最后心跳时间
     */
    private Long lastHeartbeatAt = System.currentTimeMillis();
    
    /**
     * 注册时间
     */
    private Long registeredAt = System.currentTimeMillis();
    
    /**
     * 是否动态注册 (true: 通过 API 注册, false: Nacos 自动发现)
     */
    private Boolean dynamicallyRegistered = false;
}
