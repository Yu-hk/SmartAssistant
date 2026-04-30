package com.example.smartassistant.router.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 发现配置
 * 从 application.yml 中读取可配置的 Agent 服务列表
 */
@Data
@Component
@ConfigurationProperties(prefix = "router.agent-discovery")
public class AgentDiscoveryConfig {
    
    /**
     * 需要监听的 Agent 服务名称列表
     * 默认值会在 application.yml 中配置
     */
    private List<String> watchedServices = new ArrayList<>();
    
    /**
     * 服务名称后缀过滤规则
     * 例如: ["-service", "-agent"]
     */
    private List<String> serviceNameSuffixes = new ArrayList<>();
    
    /**
     * 需要排除的服务名称列表
     * 例如: ["router-service"]
     */
    private List<String> excludedServices = new ArrayList<>();
    
    /**
     * Nacos 命名空间
     */
    private String namespace = "";
    
    /**
     * Nacos 分组
     */
    private String group = "DEFAULT_GROUP";
    
    /**
     * 是否启用自动发现
     */
    private boolean enabled = true;
    
    /**
     * 发现超时时间（毫秒）
     */
    private long discoveryTimeout = 5000;
    
    /**
     * 缓存刷新间隔（毫秒）
     */
    private long cacheRefreshInterval = 3600000; // 1小时
    
    /**
     * 心跳超时时间（毫秒）
     */
    private long heartbeatTimeout = 120000; // 2分钟
}
