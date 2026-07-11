/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.agent.FeedbackLog;
import com.example.smartassistant.common.agent.ReActProfileRegistry;
import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.prompt.PromptBuilder;
import com.example.smartassistant.common.tool.provider.ToolProvider;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.common.rag.trace.StageTraceRecorder;
import com.example.smartassistant.service.monitoring.OrderMetricsCollector;
import io.micrometer.observation.ObservationRegistry;
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
 * Order Agent 配置类。
 *
 * <p>工具设计原则（遵循 Tool Context Engineering）：
 * <ul>
 *   <li>参数化查询替代 TextToSqlTool — 不让 LLM 直接写 SQL</li>
 *   <li>高风险操作（支付/退款）需二次确认</li>
 *   <li>工具描述包含适用/不适用场景</li>
 * </ul>
 */
@Configuration
@ComponentScan(basePackages = {
    "com.example.smartassistant",
    "com.example.smartassistant.toolregistry.tool"
})
@Slf4j
public class OrderAgentConfig {

    @Value("${spring.application.name}")
    private String agentName;

    @Value("${agent.tool-tag:ORDER}")
    private String toolTag;

    @Value("${tool-registry.refresh-interval-seconds:60}")
    private int refreshIntervalSec;

    @Value("classpath:prompts/order-system-prompt.txt")
    private Resource systemPromptResource;

    @Bean
    public SmartReActAgent orderAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            ToolProvider toolProvider,
            OrderMetricsCollector metricsCollector,
            ObservationRegistry observationRegistry,
            AiChatService aiChatService,
            @Autowired(required = false) ReActProfileRegistry reactProfileRegistry) {

        log.info("[OrderAgent] 初始化 Agent: agentName={}, toolTag={}", agentName, toolTag);

        List<ToolCallback> toolList = toolProvider.getToolCallbacks(toolTag);
        log.info("[OrderAgent] 注册 {} 个工具（从 ToolProvider 获取）", toolList.size());

        // ⭐ 构建 ChatClient（Advisor 链由 AiChatService 统一装配，消除模块级样板）
        ChatClient chatClient = aiChatService.buildChatClient(chatModel);
        log.info("[OrderAgent] ChatClient 由 AiChatService 统一装配 Advisor 链");

        SmartReActAgent agent = new SmartReActAgent(chatModel)
                .withChatClient(chatClient)
                .withMetrics(metricsCollector)
                .withProfile("order", reactProfileRegistry)
                .withObservationRegistry(observationRegistry)
                .withFeedbackLog(new FeedbackLog())
                .withPreset(PromptBuilder.build()
                        .withServicePrompt(buildSystemPrompt())
                        .assemble(), toolList);

        // 启动定时刷新（仅当配置了刷新间隔且 > 0）
        if (refreshIntervalSec > 0) {
            startToolRefresh(toolProvider, agent);
        }

        return agent;
    }

    private String buildSystemPrompt() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[OrderAgent] 系统提示词文件加载失败，使用默认提示词: {}", e.getMessage());
            return "你是一个专业的电商客服助手。根据用户需求调用工具获取信息，给出推荐。";
        }
    }

    /**
     * 启动定时工具刷新任务。
     * <p>
     * 每 {@code tool-registry.refresh-interval-seconds} 秒从 ToolProvider 重新拉取工具列表，
     * 若工具列表发生变化则热更新到 Agent，无需重启。
     * </p>
     */
    private void startToolRefresh(ToolProvider toolProvider, SmartReActAgent agent) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "order-tool-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                List<ToolCallback> newTools = toolProvider.getToolCallbacks(toolTag);
                log.debug("[OrderAgent] 工具刷新完成: {} 个工具", newTools.size());
                agent.refreshTools(newTools);
            } catch (Exception e) {
                log.warn("[OrderAgent] 工具刷新失败，等待下次重试: {}", e.getMessage());
            }
        }, refreshIntervalSec, refreshIntervalSec, TimeUnit.SECONDS);
        log.info("[OrderAgent] 工具热刷新已启动: interval={}s", refreshIntervalSec);
    }

    /**
     * ⭐ P1 全阶段 trace 记录器（Redis 可用时持久化到 a2a:stage:trace:{requestId}，否则纯内存）。
     */
    @Bean
    public StageTraceRecorder stageTraceRecorder(
            @Autowired(required = false) org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        return new StageTraceRecorder(redisTemplate);
    }
}
