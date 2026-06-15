/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.agent.ProductAgent;
import com.example.smartassistant.common.agent.AgentProxyFactory;
import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.tools.ProductTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Product Agent 配置（简化版）。
 * <p>
 * 使用 {@link AgentProxyFactory} 自动完成 Agent 构建。
 * </p>
 */
@Configuration
@Slf4j
public class FoodRecommendationAgentConfig {

    @Bean
    public SmartReActAgent productAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            ProductTools productTools) {

        log.info("[ProductAgent] 通过 AgentProxyFactory 创建 Agent");
        return AgentProxyFactory.createAgent(ProductAgent.class, chatModel, productTools);
    }
}
