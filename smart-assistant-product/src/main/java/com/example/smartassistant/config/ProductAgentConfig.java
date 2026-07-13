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
import com.example.smartassistant.common.gateway.tool.meta.DiscoverToolsTool;
import com.example.smartassistant.common.prompt.PromptBuilder;
import com.example.smartassistant.common.tool.client.ToolRegistryProperties;
import com.example.smartassistant.common.tool.provider.ToolProvider;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.common.rag.trace.StageTraceRecorder;
import com.example.smartassistant.common.skill.SkillPackageManager;
import com.example.smartassistant.service.monitoring.ProductMetricsCollector;
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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Product Agent 配置类
 *
 * <p>系统提示词外部化在 {@code prompts/product-system-prompt.txt}。</p>
 */
@Configuration
@ComponentScan(basePackages = {
    "com.example.smartassistant"
})
@Slf4j
public class ProductAgentConfig {

    @Value("${spring.application.name}")
    private String agentName;

    @Value("${agent.tool-tag:PRODUCT}")
    private String toolTag;

    @Value("${tool-registry.refresh-interval-seconds:60}")
    private int refreshIntervalSec;

    @Value("classpath:prompts/product-system-prompt.txt")
    private Resource systemPromptResource;

    /**
     * T2d：发现元工具（可选注入，由特性开关 {@code tool-registry.t2-mcp-discovery-enabled} 控制）。
     */
    @Autowired(required = false)
    private DiscoverToolsTool discoverToolsTool;

    @Autowired
    private ToolRegistryProperties toolRegistryProperties;

    /**
     * 主 Agent Bean - 供 StreamFoodAgentService 使用
     */
    @Bean
    public SmartReActAgent productAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            ToolProvider toolProvider,
            ProductMetricsCollector metricsCollector,
            @Autowired(required = false) SkillPackageManager skillPackageManager,
            AiChatService aiChatService,
            @Autowired(required = false) ReActProfileRegistry reactProfileRegistry) {

        log.info("[ProductAgent] 初始化 Agent: agentName={}, toolTag={}", agentName, toolTag);

        List<ToolCallback> toolList = toolProvider.getToolCallbacks(toolTag);
        log.info("[ProductAgent] 注册 {} 个工具（从 ToolProvider 获取）", toolList.size());

        // ⭐ T2d：若特性开关启用且 DiscoverToolsTool 可用，注入 discover_tools 元工具
        List<ToolCallback> effectiveToolList = toolList;
        if (discoverToolsTool != null && toolRegistryProperties.isT2McpDiscoveryEnabled()) {
            log.info("[ProductAgent] T2d 发现机制已启用，注入 discover_tools 元工具");
            ToolCallback[] discoverCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(discoverToolsTool)
                    .build()
                    .getToolCallbacks();
            effectiveToolList = new ArrayList<>(toolList);
            for (ToolCallback cb : discoverCallbacks) {
                effectiveToolList.add(cb);
                log.info("[ProductAgent] 已添加元工具: {}", cb.getToolDefinition().name());
            }
        }

        // 构建系统 prompt（含技能包指令）
        String basePrompt = buildSystemPrompt();
        String skillPrompt = "";
        if (skillPackageManager != null) {
            skillPrompt = skillPackageManager.buildAgentSkillPrompt(agentName);
            if (!skillPrompt.isBlank()) {
                log.info("[ProductAgent] 注入 {} 个技能包到 Agent", 
                        skillPackageManager.getAgentSkills(agentName).size());
            }
        }
        String fullSystemPrompt = skillPrompt.isBlank() ? basePrompt : basePrompt + "\n" + skillPrompt;

        // ⭐ 构建 ChatClient（Advisor 链由 AiChatService 统一装配）
        ChatClient chatClient = aiChatService.buildChatClient(chatModel);
        log.info("[ProductAgent] ChatClient 由 AiChatService 统一装配 Advisor 链");

        SmartReActAgent agent = new SmartReActAgent(chatModel)
                .withChatClient(chatClient)
                .withMetrics(metricsCollector)
                .withProfile("product", reactProfileRegistry)
                .withFeedbackLog(new FeedbackLog())
                .withPreset(PromptBuilder.build()
                        .withServicePrompt(fullSystemPrompt)
                        .assemble(), effectiveToolList);

        // ⭐ T2d：Agent 创建后设置注册器
        if (discoverToolsTool != null && toolRegistryProperties.isT2McpDiscoveryEnabled()) {
            discoverToolsTool.setToolRegistrar(callbacks ->
                    agent.registerDiscoveredTool(callbacks.toArray(new ToolCallback[0])));
            log.info("[ProductAgent] DiscoverToolsTool 注册器已绑定到 Agent");
        }

        // 启动定时刷新
        if (refreshIntervalSec > 0) {
            startToolRefresh(toolProvider, agent);
        }

        return agent;
    }

    private String buildSystemPrompt() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[FoodAgent] 系统提示词文件加载失败，使用默认提示词: {}", e.getMessage());
            return "你是一个专业的商品咨询助手。根据用户需求调用工具获取商品信息，给出回答。";
        }
    }

    /**
     * 启动定时工具刷新任务。
     * 每 {@code tool-registry.refresh-interval-seconds} 秒从 ToolProvider 重新拉取工具列表，
     * 若工具列表发生变化则热更新到 Agent，无需重启。
     */
    private void startToolRefresh(ToolProvider toolProvider, SmartReActAgent agent) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "product-tool-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                List<ToolCallback> newTools = toolProvider.getToolCallbacks(toolTag);
                log.debug("[ProductAgent] 工具刷新完成: {} 个工具", newTools.size());
                agent.refreshTools(newTools);
            } catch (Exception e) {
                log.warn("[ProductAgent] 工具刷新失败，等待下次重试: {}", e.getMessage());
            }
        }, refreshIntervalSec, refreshIntervalSec, TimeUnit.SECONDS);
        log.info("[ProductAgent] 工具热刷新已启动: interval={}s", refreshIntervalSec);
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
