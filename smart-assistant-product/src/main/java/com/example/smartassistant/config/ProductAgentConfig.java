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
import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.common.rag.trace.StageTraceRecorder;
import com.example.smartassistant.common.skill.SkillPackageManager;
import com.example.smartassistant.product.tool.KnowledgeQueryTool;
import com.example.smartassistant.product.tool.ProductMemoryTool;
import com.example.smartassistant.product.tool.ProductTools;
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
import java.util.Arrays;
import java.util.List;

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

    @Value("classpath:prompts/product-system-prompt.txt")
    private Resource systemPromptResource;

    /**
     * T2d：发现元工具（由特性开关 {@code tool-registry.t2-mcp-discovery-enabled} 控制）。
     */
    @Autowired(required = false)
    private DiscoverToolsTool discoverToolsTool;

    /**
     * 主 Agent Bean - 供 StreamFoodAgentService 使用
     */
    @Bean
    public SmartReActAgent productAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            ProductTools productTools,
            ProductMemoryTool productMemoryTool,
            KnowledgeQueryTool knowledgeQueryTool,
            ProductMetricsCollector metricsCollector,
            @Autowired(required = false) SkillPackageManager skillPackageManager,
            AiChatService aiChatService,
            @Autowired(required = false) ReActProfileRegistry reactProfileRegistry) {

        log.info("[ProductAgent] 初始化 Agent: agentName={}", agentName);

        // 从本模块 @Component 工具 Bean 直接扫描加载
        ToolCallback[] moduleToolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(productTools, productMemoryTool, knowledgeQueryTool)
                .build()
                .getToolCallbacks();
        List<ToolCallback> toolList = new ArrayList<>(Arrays.asList(moduleToolCallbacks));

        log.info("[ProductAgent] 加载 {} 个本模块工具", toolList.size());

        // ⭐ T2d：注入 discover_tools 元工具
        List<ToolCallback> effectiveToolList = toolList;
        if (discoverToolsTool != null) {
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
        if (discoverToolsTool != null) {
            discoverToolsTool.setToolRegistrar(callbacks ->
                    agent.registerDiscoveredTool(callbacks.toArray(new ToolCallback[0])));
            log.info("[ProductAgent] DiscoverToolsTool 注册器已绑定到 Agent");
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
     * ⭐ P1 全阶段 trace 记录器（Redis 可用时持久化到 a2a:stage:trace:{requestId}，否则纯内存）。
     */
    @Bean
    public StageTraceRecorder stageTraceRecorder(
            @Autowired(required = false) org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        return new StageTraceRecorder(redisTemplate);
    }
}
