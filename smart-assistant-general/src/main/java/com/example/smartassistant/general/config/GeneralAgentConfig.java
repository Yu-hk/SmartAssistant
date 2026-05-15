/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.smartassistant.general.tool.ImageTools;
import com.example.smartassistant.general.tool.GeneralTools;
import com.example.smartassistant.general.tool.WeatherTool;
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
 * General Agent 配置
 *
 * <p>系统提示词外部化在 {@code prompts/general-system-prompt.txt}。</p>
 */
@Configuration
@Slf4j
public class GeneralAgentConfig {

    @Value("${spring.ai.alibaba.a2a.server.card.name}")
    private String agentName;

    @Value("classpath:prompts/general-system-prompt.txt")
    private Resource systemPromptResource;

    /**
     * 通用对话 Agent Bean
     * 注册通用工具和图像工具，支持 ReAct 模式
     */
    @Bean
    public ReactAgent generalChatAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            GeneralTools generalTools,
            ImageTools imageTools,
            WeatherTool weatherTool) {

        log.info("[GeneralAgent] 初始化通用对话 Agent: agentName={}", agentName);

        // 注册所有工具（通用工具 + 图像工具 + 天气工具）
        MethodToolCallbackProvider generalToolProvider = MethodToolCallbackProvider.builder()
                .toolObjects(generalTools)
                .build();
        MethodToolCallbackProvider imageToolProvider = MethodToolCallbackProvider.builder()
                .toolObjects(imageTools)
                .build();
        MethodToolCallbackProvider weatherToolProvider = MethodToolCallbackProvider.builder()
                .toolObjects(weatherTool)
                .build();
        List<ToolCallback> toolCallbacks = new ArrayList<>();
        toolCallbacks.addAll(List.of(generalToolProvider.getToolCallbacks()));
        toolCallbacks.addAll(List.of(imageToolProvider.getToolCallbacks()));
        toolCallbacks.addAll(List.of(weatherToolProvider.getToolCallbacks()));

        log.info("[GeneralAgent] 注册 {} 个工具（通用 {} + 图像 {} + 天气 {}）",
                toolCallbacks.size(),
                generalToolProvider.getToolCallbacks().length,
                imageToolProvider.getToolCallbacks().length,
                weatherToolProvider.getToolCallbacks().length);

        return ReactAgent.builder()
                .name(agentName)
                .description("通用对话智能体 - 闲聊、问答、数学计算、单位转换")
                .model(chatModel)
                .systemPrompt(buildSystemPrompt())
                .tools(toolCallbacks.toArray(new ToolCallback[0]))
                .outputKey("output")
                .build();
    }

    private String buildSystemPrompt() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[GeneralAgent] 系统提示词文件加载失败，使用默认提示词: {}", e.getMessage());
            return "你是一个友好的通用对话助手，擅长闲聊、问答以及各类日常实用计算。";
        }
    }
}
