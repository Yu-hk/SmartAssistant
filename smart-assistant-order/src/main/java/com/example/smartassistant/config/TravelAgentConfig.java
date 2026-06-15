/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.agent.OrderAgent;
import com.example.smartassistant.common.agent.AgentProxyFactory;
import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.tools.OrderTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Travel Agent 配置（简化版）。
 * <p>
 * 使用 {@link AgentProxyFactory} 自动完成 Agent 构建，
 * 无需手动注册工具、加载系统提示词、配置 ReAct 参数。
 * </p>
 */
@Configuration
@Slf4j
public class TravelAgentConfig {

    @Bean
    public SmartReActAgent orderAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            OrderTools orderTools) {

        log.info("[OrderAgent] 通过 AgentProxyFactory 创建 Agent");
        return AgentProxyFactory.createAgent(OrderAgent.class, chatModel, orderTools);
    }
}
