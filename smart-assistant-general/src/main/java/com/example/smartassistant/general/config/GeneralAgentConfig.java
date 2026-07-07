/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.config;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.metrics.AgentMetricsCollector;
import com.example.smartassistant.common.prompt.PromptBuilder;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.common.tool.AiToolRegistry;
import com.example.smartassistant.general.service.monitoring.GeneralMetricsCollector;
import com.example.smartassistant.general.tool.GeneralMemoryTool;
import com.example.smartassistant.general.tool.GeneralTools;
import com.example.smartassistant.general.tool.ImageTools;
import com.example.smartassistant.general.tool.WeatherTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Value("${spring.application.name:general-agent}")
    private String agentName;

    @Value("classpath:prompts/general-system-prompt.txt")
    private Resource systemPromptResource;

    /**
     * 通用对话 Agent Bean
     * 注册通用工具、图像工具和天气工具
     */
    @Bean
    public SmartReActAgent generalChatAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            GeneralTools generalTools,
            ImageTools imageTools,
            WeatherTool weatherTool,
            GeneralMemoryTool generalMemoryTool,
            GeneralMetricsCollector metricsCollector,
            AiChatService aiChatService,
            AiToolRegistry aiToolRegistry) {

        log.info("[GeneralAgent] 初始化通用对话 Agent: agentName={}", agentName);

        // 注册所有工具（通用工具 + 图像工具 + 天气工具 + 记忆工具）
        List<ToolCallback> toolCallbacks = aiToolRegistry.assemble(generalTools, imageTools, weatherTool, generalMemoryTool);

        log.info("[GeneralAgent] 注册 {} 个工具（通用 + 图像 + 天气 + 记忆）", toolCallbacks.size());

        // ⭐ 构建 ChatClient（Advisor 链由 AiChatService 统一装配）
        ChatClient chatClient = aiChatService.buildChatClient(chatModel);
        log.info("[GeneralAgent] ChatClient 由 AiChatService 统一装配 Advisor 链");

        return new SmartReActAgent(chatModel)
                .withChatClient(chatClient)
                .withMetrics(metricsCollector)
                .withMaxIterations(10)
                .withTimeoutMs(60_000)
                .withPreset(PromptBuilder.build()
                        .withServicePrompt(buildSystemPrompt())
                        .assemble(), toolCallbacks);
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
