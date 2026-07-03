/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * 应用启动时将 Product Agent 注册到 Nacos A2A Registry。
 *
 * @see com.example.smartassistant.config.NacosAgentCardConfig
 */
@Component
@ConditionalOnBean(AiService.class)
public class NacosAgentCardRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NacosAgentCardRegistrar.class);

    private final AiService aiService;
    private final AgentCard agentCard;
    private final int serverPort;

    private volatile boolean registered = false;

    public NacosAgentCardRegistrar(AiService aiService, AgentCard agentCard,
                                   @Value("${server.port:8084}") int serverPort) {
        this.aiService = aiService;
        this.agentCard = agentCard;
        this.serverPort = serverPort;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String agentName = agentCard.getName();
            String version = agentCard.getVersion();

            log.info("[NacosA2A] 开始注册 AgentCard: name={}, version={}", agentName, version);

            aiService.releaseAgentCard(agentCard);
            log.info("[NacosA2A] AgentCard [{}] v{} 已发布到 Nacos A2A Registry",
                agentName, version);

            aiService.registerAgentEndpoint(agentName, version, "localhost", serverPort, "JSONRPC");
            log.info("[NacosA2A] Agent 端点已注册: localhost:{}", serverPort);

            registered = true;
        } catch (Exception e) {
            log.error("[NacosA2A] AgentCard 注册失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (!registered) {
            return;
        }
        try {
            aiService.deregisterAgentEndpoint(
                agentCard.getName(), agentCard.getVersion(), "localhost", serverPort);
            log.info("[NacosA2A] Agent 端点已注销: name={}", agentCard.getName());
        } catch (Exception e) {
            log.warn("[NacosA2A] Agent 端点注销失败: {}", e.getMessage());
        }
    }
}
