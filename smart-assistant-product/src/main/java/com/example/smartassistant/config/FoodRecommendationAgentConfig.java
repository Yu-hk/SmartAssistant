/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.prompt.PromptBuilder;
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
     * 主 Agent Bean - 供 StreamFoodAgentService 使用
     */
    @Bean
    public SmartReActAgent productAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            com.example.smartassistant.tools.ProductTools productTools) {

        log.info("[ProductAgent] 初始化 Agent: agentName={}", agentName);

        MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(productTools)
                .build();
        ToolCallback[] allTools = provider.getToolCallbacks();
        List<ToolCallback> toolList = new ArrayList<>();
        toolList.addAll(List.of(allTools));

        log.info("[ProductAgent] 注册 {} 个工具", toolList.size());

        return new SmartReActAgent(chatModel)
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
            return "你是一个专业的美食推荐助手。根据用户需求调用工具获取信息，给出推荐。";
        }
    }
}
