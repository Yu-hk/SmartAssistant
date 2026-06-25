/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.metrics.AgentMetricsCollector;
import com.example.smartassistant.common.prompt.PromptBuilder;
import com.example.smartassistant.service.monitoring.ProductMetricsCollector;
import com.example.smartassistant.tools.KnowledgeQueryTool;
import com.example.smartassistant.tools.ProductMemoryTool;
import com.example.smartassistant.tools.ProductTools;
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
 * Product Agent 配置类
 *
 * <p>系统提示词外部化在 {@code prompts/product-system-prompt.txt}。</p>
 */
@Configuration
@Slf4j
public class ProductAgentConfig {

    @Value("${spring.application.name}")
    private String agentName;

    @Value("classpath:prompts/product-system-prompt.txt")
    private Resource systemPromptResource;

    /**
     * 主 Agent Bean - 供 StreamFoodAgentService 使用
     */
    @Bean
    public SmartReActAgent productAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            ProductTools productTools,
            KnowledgeQueryTool knowledgeQueryTool,
            ProductMemoryTool productMemoryTool,
            ProductMetricsCollector metricsCollector) {

        log.info("[ProductAgent] 初始化 Agent: agentName={}", agentName);

        MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(productTools)
                .build();
        ToolCallback[] allTools = provider.getToolCallbacks();
        List<ToolCallback> toolList = new ArrayList<>();
        toolList.addAll(List.of(allTools));

        // ⭐ 注册知识库查询工具
        MethodToolCallbackProvider kbProvider = MethodToolCallbackProvider.builder()
                .toolObjects(knowledgeQueryTool)
                .build();
        toolList.addAll(List.of(kbProvider.getToolCallbacks()));

        // ⭐ 注册记忆工具
        MethodToolCallbackProvider memProvider = MethodToolCallbackProvider.builder()
                .toolObjects(productMemoryTool)
                .build();
        toolList.addAll(List.of(memProvider.getToolCallbacks()));

        log.info("[ProductAgent] 注册 {} 个工具（商品查询 + 知识库 + 记忆）", toolList.size());

        return new SmartReActAgent(chatModel)
                .withMetrics(metricsCollector)
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
            log.warn("[FoodAgent] 系统提示词文件加载失败，使用默认提示词: {}", e.getMessage());
            return "你是一个专业的商品咨询助手。根据用户需求调用工具获取商品信息，给出回答。";
        }
    }
}
