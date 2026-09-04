/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.exception.NacosException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * Nacos A2A Registry 配置 — 以 Product Agent 身份注册 AgentCard。
 * <p>
 * AgentCard 元数据从 {@code nacos.a2a.registry.agent-card.*} 配置读取。
 *
 * @since 1.0.0
 */
@Configuration
@ConditionalOnProperty(name = "nacos.a2a.registry.enabled", havingValue = "true", matchIfMissing = false)
public class NacosAgentCardConfig {

    @Value("${spring.cloud.nacos.discovery.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    @Value("${spring.cloud.nacos.discovery.username:${NACOS_USERNAME:nacos}}")
    private String username;

    @Value("${spring.cloud.nacos.discovery.password:${NACOS_PASSWORD:nacos123}}")
    private String password;

    @Value("${spring.cloud.nacos.discovery.namespace:}")
    private String namespace;

    @Bean
    @ConfigurationProperties(prefix = "nacos.a2a.registry.agent-card")
    public AgentCard productAgentCard() {
        return new AgentCard();
    }

    @Bean(destroyMethod = "shutdown")
    public AiService aiService() throws NacosException {
        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        props.setProperty(PropertyKeyConst.USERNAME, username);
        props.setProperty(PropertyKeyConst.PASSWORD, password);
        if (namespace != null && !namespace.isEmpty()) {
            props.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        }
        return AiFactory.createAiService(props);
    }
}
