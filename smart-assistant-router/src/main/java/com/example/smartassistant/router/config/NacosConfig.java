/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.config;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * Nacos 配置类 — 创建 NamingService 和 AiService Bean。
 * <p>
 * <ul>
 *   <li>NamingService — 服务实例发现（Spring Cloud Alibaba Nacos Discovery）</li>
 *   <li>AiService — A2A Registry（Agent 元数据发现）</li>
 * </ul>
 */
@Configuration
public class NacosConfig {

    private static final Logger log = LoggerFactory.getLogger(NacosConfig.class);

    @Value("${spring.cloud.nacos.discovery.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    @Value("${nacos.discovery.namespace:}")
    private String namespace;

    @Value("${nacos.discovery.username:${NACOS_USERNAME:nacos}}")
    private String username;

    @Value("${nacos.discovery.password:${NACOS_PASSWORD:nacos123}}")
    private String password;

    /**
     * 创建 Nacos NamingService Bean（服务实例发现）
     */
    @Bean
    public NamingService namingService() throws Exception {
        Properties properties = new Properties();
        properties.put("serverAddr", serverAddr);
        properties.put("namespace", namespace);
        properties.put("username", username);
        properties.put("password", password);

        return NacosFactory.createNamingService(properties);
    }

    /**
     * 创建 Nacos A2A Registry AiService Bean（Agent 元数据发现）。
     * <p>
     * 用于订阅 AgentCard 变更，获取 Agent 的 skills/description/version 等结构化信息。
     * 与 NamingService 共享同一 Nacos 连接配置，职责不同：
     * <ul>
     *   <li>NamingService → 服务实例级发现（IP:Port 变动）</li>
     *   <li>AiService → Agent 元数据级发现（AgentCard 技能/版本变更）</li>
     * </ul>
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "nacos.a2a.discovery.enabled", havingValue = "true", matchIfMissing = false)
    public AiService aiService() throws Exception {
        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        props.setProperty(PropertyKeyConst.USERNAME, username);
        props.setProperty(PropertyKeyConst.PASSWORD, password);
        if (namespace != null && !namespace.isEmpty()) {
            props.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        }
        AiService service = AiFactory.createAiService(props);
        log.info("[NacosA2A] Router AiService 已创建（gRPC 连接）");
        return service;
    }
}
