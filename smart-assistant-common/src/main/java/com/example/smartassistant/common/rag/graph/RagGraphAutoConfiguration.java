/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.graph;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * ⭐ 多 ChatModel 环境下避免「expected single matching bean but found N」歧义：
     * 用 {@link ObjectProvider} 取第一个可用模型（图谱抽取为 best-effort，使用任一模型均可）。
     * 各服务注册的 ChatModel 组合不同（如 consumer 仅 light/ollama，order/product 含 deepSeek），
     * 故不能写死 @Qualifier 固定名称。
     */
    @Autowired(required = false)
    public RagGraphAutoConfiguration(ObjectProvider<ChatModel> chatModelProvider, AiChatService aiChatService) {
        this.chatModel = chatModelProvider.stream().findFirst().orElse(null);
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
