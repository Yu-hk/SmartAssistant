/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.config;

import com.example.smartassistant.common.agent.FeedbackLog;
import com.example.smartassistant.common.agent.ReActProfileRegistry;
import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.gateway.tool.meta.DiscoverToolsTool;
import com.example.smartassistant.common.prompt.PromptBuilder;
import com.example.smartassistant.common.rag.advisor.AiChatService;
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
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * General Agent 配置
 *
 * <p>系统提示词外部化在 {@code prompts/general-system-prompt.txt}。</p>
 */
@Configuration
@ComponentScan(basePackages = {
    "com.example.smartassistant"
})
@Slf4j
public class GeneralAgentConfig {

    @Value("${spring.application.name:general-agent}")
    private String agentName;

    @Value("classpath:prompts/general-system-prompt.txt")
    private Resource systemPromptResource;

    /**
     * T2d：发现元工具（由特性开关 {@code tool-registry.t2-mcp-discovery-enabled} 控制）。
     */
    @Autowired(required = false)
    private DiscoverToolsTool discoverToolsTool;

    /**
     * 通用对话 Agent Bean
     * 注册通用工具、图像工具和天气工具
     */
    @Bean
    public SmartReActAgent generalChatAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            WeatherTool weatherTool,
            ImageTools imageTools,
            GeneralTools generalTools,
            GeneralMemoryTool generalMemoryTool,
            GeneralMetricsCollector metricsCollector,
            AiChatService aiChatService,
            @Autowired(required = false) ReActProfileRegistry reactProfileRegistry) {

        log.info("[GeneralAgent] 初始化通用对话 Agent: agentName={}", agentName);

        // 从本模块 @Component 工具 Bean 直接扫描加载
        ToolCallback[] moduleToolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(weatherTool, imageTools, generalTools, generalMemoryTool)
                .build()
                .getToolCallbacks();
        List<ToolCallback> toolList = new ArrayList<>(Arrays.asList(moduleToolCallbacks));

        log.info("[GeneralAgent] 加载 {} 个本模块工具", toolList.size());

        // ⭐ T2d：注入 discover_tools 元工具
        List<ToolCallback> effectiveToolList = toolList;
        if (discoverToolsTool != null) {
            log.info("[GeneralAgent] T2d 发现机制已启用，注入 discover_tools 元工具");
            ToolCallback[] discoverCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(discoverToolsTool)
                    .build()
                    .getToolCallbacks();
            effectiveToolList = new ArrayList<>(toolList);
            for (ToolCallback cb : discoverCallbacks) {
                effectiveToolList.add(cb);
                log.info("[GeneralAgent] 已添加元工具: {}", cb.getToolDefinition().name());
            }
        }

        // ⭐ 构建 ChatClient（Advisor 链由 AiChatService 统一装配）
        ChatClient chatClient = aiChatService.buildChatClient(chatModel);
        log.info("[GeneralAgent] ChatClient 由 AiChatService 统一装配 Advisor 链");

        SmartReActAgent agent = new SmartReActAgent(chatModel)
                .withChatClient(chatClient)
                .withMetrics(metricsCollector)
                .withProfile("general", reactProfileRegistry)
                .withFeedbackLog(new FeedbackLog())
                .withPreset(PromptBuilder.build()
                        .withServicePrompt(buildSystemPrompt())
                        .assemble(), effectiveToolList);

        // ⭐ T2d：Agent 创建后设置注册器
        if (discoverToolsTool != null) {
            discoverToolsTool.setToolRegistrar(callbacks ->
                    agent.registerDiscoveredTool(callbacks.toArray(new ToolCallback[0])));
            log.info("[GeneralAgent] DiscoverToolsTool 注册器已绑定到 Agent");
        }

        return agent;
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
