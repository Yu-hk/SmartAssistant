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
    public ReactAgent productAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            com.example.smartassistant.tools.ProductTools productTools) {

        log.info("[ProductAgent] 初始化 Agent: agentName={}", agentName);

        MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(productTools)
                .build();
        ToolCallback[] allTools = provider.getToolCallbacks();

        log.info("[ProductAgent] 注册 {} 个工具", allTools.length);

        return ReactAgent.builder()
                .name(agentName)
                .description("商品查询、库存查询、价格查询")
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
