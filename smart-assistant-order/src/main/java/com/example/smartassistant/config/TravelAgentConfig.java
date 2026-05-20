/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.prompt.PromptBuilder;
import com.example.smartassistant.tools.OrderTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Travel Agent 配置类
 *
 * <p>系统提示词外部化在 {@code prompts/travel-system-prompt.txt}，修改无需重新编译。</p>
 */
@Configuration
@Slf4j
public class TravelAgentConfig {

    @Value("${spring.ai.alibaba.a2a.server.card.name}")
    private String agentName;

    @Value("classpath:prompts/travel-system-prompt.txt")
    private Resource systemPromptResource;

    @Bean
    public SmartReActAgent orderAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            OrderTools orderTools) {

        log.info("[OrderAgent] 初始化 Agent: agentName={}", agentName);

        List<ToolCallback> allCallbacks = new ArrayList<>();
        for (var tool : List.of(orderTools)) {
            allCallbacks.addAll(List.of(
                    MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks()));
        }

        List<ToolCallback> toolList = new ArrayList<>(allCallbacks);
        log.info("[OrderAgent] 注册 {} 个工具", toolList.size());

        return new SmartReActAgent(chatModel)
                .withMaxIterations(10)
                .withTimeoutMs(60_000)
                .withPreset(PromptBuilder.build()
                        .withServicePrompt(buildSystemPrompt())
                        .assemble(), toolList);
    }

    private String buildSystemPrompt() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[TravelAgent] 系统提示词文件加载失败，使用默认提示词: {}", e.getMessage());
            return "你是一个专业的出行规划助手。根据用户需求调用工具获取信息，给出推荐。";
        }
    }
}
