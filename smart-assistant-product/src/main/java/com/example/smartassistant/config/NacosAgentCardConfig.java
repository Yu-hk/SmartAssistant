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
import com.alibaba.nacos.api.ai.model.a2a.AgentSkill;
import com.alibaba.nacos.api.exception.NacosException;
import com.example.smartassistant.common.config.NacosAgentCardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Nacos A2A Registry 配置 — 以 Product Agent 身份注册 AgentCard。
 * <p>
 * AgentCard 元数据从 {@code nacos.a2a.registry.agent-card.*} 配置读取。
 *
 * @since 1.0.0
 */
@Configuration
@EnableConfigurationProperties(NacosAgentCardProperties.class)
@ConditionalOnProperty(name = "nacos.a2a.registry.enabled", havingValue = "true", matchIfMissing = false)
public class NacosAgentCardConfig {

    private static final Logger log = LoggerFactory.getLogger(NacosAgentCardConfig.class);

    private final NacosAgentCardProperties properties;

    @Value("${spring.cloud.nacos.discovery.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    @Value("${spring.cloud.nacos.discovery.username:${NACOS_USERNAME:nacos}}")
    private String username;

    @Value("${spring.cloud.nacos.discovery.password:${NACOS_PASSWORD:nacos123}}")
    private String password;

    @Value("${spring.cloud.nacos.discovery.namespace:}")
    private String namespace;

    public NacosAgentCardConfig(NacosAgentCardProperties properties) {
        this.properties = properties;
    }

    @Bean
    public AgentCard productAgentCard() {
        AgentCard card = new AgentCard();
        card.setName(properties.getName());
        card.setDescription(properties.getDescription());
        card.setUrl(properties.getUrl());
        card.setVersion(properties.getVersion());
        card.setProtocolVersion(properties.getProtocolVersion());
        card.setPreferredTransport(properties.getPreferredTransport());

        card.setSkills(properties.getSkills().stream()
            .map(this::toAgentSkill)
            .collect(Collectors.toList()));

        log.info("[NacosA2A] AgentCard 配置已加载: name={}, version={}, skills={}",
            card.getName(), card.getVersion(),
            card.getSkills() != null ? card.getSkills().size() : 0);
        return card;
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

    private AgentSkill toAgentSkill(NacosAgentCardProperties.Skill prop) {
        AgentSkill skill = new AgentSkill();
        skill.setId(prop.getId());
        skill.setName(prop.getName());
        skill.setDescription(prop.getDescription());
        skill.setTags(prop.getTags());
        skill.setExamples(prop.getExamples());
        return skill;
    }
}
