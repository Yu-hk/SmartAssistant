/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.smartassistant.tool.FoodRecommendationTool;
import com.example.smartassistant.tool.PersonalizedRestaurantRecommendationTool;
import com.example.smartassistant.tool.SmartRestaurantRecommendationTool;
import com.example.smartassistant.agent.FoodAgentTools;
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
 * Food Agent 配置类
 *
 * <p>系统提示词外部化在 {@code prompts/food-system-prompt.txt}。</p>
 */
@Configuration
@Slf4j
public class FoodRecommendationAgentConfig {

    @Value("${spring.ai.alibaba.a2a.server.card.name}")
    private String agentName;

    @Value("classpath:prompts/food-system-prompt.txt")
    private Resource systemPromptResource;

    /**
     * 主 Agent Bean - 供 A2A Server 使用
     */
    @Bean
    public ReactAgent foodRecommendationAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            FoodAgentTools foodAgentTools) {

        log.info("[FoodAgent] 初始化 Agent: agentName={}", agentName);

        // 收集所有 ToolCallback

        // FoodAgentTools（核心工具集 - 聚合了所有具体 Tool）
        MethodToolCallbackProvider foodAgentProvider = MethodToolCallbackProvider.builder()
                .toolObjects(foodAgentTools)
                .build();
        List<ToolCallback> allCallbacks = new ArrayList<>(List.of(foodAgentProvider.getToolCallbacks()));

        // ⚠️ 注意：不再单独注册具体 Tool，避免工具名重复冲突
        // 具体 Tool 由 FoodAgentTools 内部调用

        ToolCallback[] allTools = allCallbacks.toArray(new ToolCallback[0]);

        log.info("[FoodAgent] 发现 {} 个工具", allTools.length);

        // 构建 Agent
        return ReactAgent.builder()
                .name(agentName)
                .description("美食推荐智能体 - 提供特色菜查询、附近餐厅推荐、个性化推荐等服务")
                .model(chatModel)
                .systemPrompt(buildSystemPrompt())
                .tools(allTools)
                .outputKey("output")
                .build();
    }

    private String buildSystemPrompt() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[FoodAgent] 系统提示词文件加载失败，使用默认提示词: {}", e.getMessage());
            return "你是一个专业的美食推荐助手。根据用户需求调用工具获取信息，给出推荐。";
        }
    }
}
