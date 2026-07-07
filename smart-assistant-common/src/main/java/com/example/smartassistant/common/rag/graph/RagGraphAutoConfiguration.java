/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.graph;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识图谱自动配置 — 在持有 {@link ChatModel} Bean 的 Spring Boot 应用中，
 * 自动装配 {@link KnowledgeGraphService}（使用真实 {@link LlmEntityExtractor}）。
 * <p>
 * 应用侧只需将本 Bean 注入到 {@code KnowledgeIngestionService}
 * （{@code ingestionService.setKnowledgeGraphService(graphService)}），
 * 摄取流程即可联动抽取实体 / 关系构建图谱。若应用已自定义
 * {@link KnowledgeGraphService} Bean，则本配置让位（{@code @ConditionalOnMissingBean}）。
 * </p>
 */
@Configuration
@ConditionalOnBean(ChatModel.class)
public class RagGraphAutoConfiguration {

    private final ChatModel chatModel;
    private final AiChatService aiChatService;

    @Autowired(required = false)
    public RagGraphAutoConfiguration(ChatModel chatModel, AiChatService aiChatService) {
        this.chatModel = chatModel;
        this.aiChatService = aiChatService;
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeGraphService.class)
    public KnowledgeGraphService knowledgeGraphService() {
        KnowledgeGraphService graphService = new KnowledgeGraphService();
        graphService.setExtractor(new LlmEntityExtractor(chatModel, aiChatService));
        return graphService;
    }
}
