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
import com.example.smartassistant.common.prompt.PromptBuilder;
import com.example.smartassistant.common.tool.provider.ToolProvider;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.general.service.monitoring.GeneralMetricsCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * General Agent 配置
 *
 * <p>系统提示词外部化在 {@code prompts/general-system-prompt.txt}。</p>
 */
@Configuration
@ComponentScan(basePackages = {
    "com.example.smartassistant",
    "com.example.smartassistant.toolregistry.tool"
})
@Slf4j
public class GeneralAgentConfig {

    @Value("${spring.application.name:general-agent}")
    private String agentName;

    @Value("${agent.tool-tag:GENERAL}")
    private String toolTag;

    @Value("${tool-registry.refresh-interval-seconds:60}")
    private int refreshIntervalSec;

    @Value("classpath:prompts/general-system-prompt.txt")
    private Resource systemPromptResource;

    /**
     * 通用对话 Agent Bean
     * 注册通用工具、图像工具和天气工具
     */
    @Bean
    public SmartReActAgent generalChatAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            ToolProvider toolProvider,
            GeneralMetricsCollector metricsCollector,
            AiChatService aiChatService,
            @Autowired(required = false) ReActProfileRegistry reactProfileRegistry) {

        log.info("[GeneralAgent] 初始化通用对话 Agent: agentName={}, toolTag={}", agentName, toolTag);

        // 通过 ToolProvider 发现工具，不再注入具体 Bean
        List<ToolCallback> toolCallbacks = toolProvider.getToolCallbacks(toolTag);

        log.info("[GeneralAgent] ToolProvider 发现 {} 个工具（tag={}）", toolCallbacks.size(), toolTag);

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
                        .assemble(), toolCallbacks);

        // 启动定时刷新（通过 ToolProvider）
        if (refreshIntervalSec > 0) {
            startToolRefresh(toolProvider, agent);
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

    /**
     * 启动定时工具刷新任务（ToolProvider 模式）。
     * 每 {@code tool-registry.refresh-interval-seconds} 秒通过 ToolProvider 动态扫描工具，
     * 若工具列表发生变化则热更新到 Agent，无需重启。
     */
    private void startToolRefresh(ToolProvider toolProvider, SmartReActAgent agent) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "general-tool-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                List<ToolCallback> newTools = toolProvider.getToolCallbacks(toolTag);
                log.debug("[GeneralAgent] 工具刷新完成: {} 个工具", newTools.size());
                agent.refreshTools(newTools);
            } catch (Exception e) {
                log.warn("[GeneralAgent] 工具刷新失败，等待下次重试: {}", e.getMessage());
            }
        }, refreshIntervalSec, refreshIntervalSec, TimeUnit.SECONDS);
        log.info("[GeneralAgent] 工具热刷新已启动: interval={}s (ToolProvider 模式)", refreshIntervalSec);
    }
}
