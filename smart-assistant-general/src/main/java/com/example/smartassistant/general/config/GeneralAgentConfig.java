/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.config;

import com.example.smartassistant.common.agent.AgentProxyFactory;
import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.general.agent.GeneralAgent;
import com.example.smartassistant.general.tool.GeneralTools;
import com.example.smartassistant.general.tool.ImageTools;
import com.example.smartassistant.general.tool.WeatherTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * General Agent 配置（简化版）。
 * <p>
 * 使用 {@link AgentProxyFactory} 自动完成 Agent 构建，
 * 消除 94 行 → 18 行的样板代码。
 * </p>
 */
@Configuration
@Slf4j
public class GeneralAgentConfig {

    @Bean
    public SmartReActAgent generalChatAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            GeneralTools generalTools,
            ImageTools imageTools,
            WeatherTool weatherTool) {

        log.info("[GeneralAgent] 通过 AgentProxyFactory 创建 Agent");
        return AgentProxyFactory.createAgent(GeneralAgent.class, chatModel,
                generalTools, imageTools, weatherTool);
    }
}
