/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.common.rag.graph.KnowledgeGraphService;
import com.example.smartassistant.common.rag.graph.LlmEntityExtractor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Product 的知识图谱装配。
 *
 * <p>实体关系抽取目前只参与商品知识检索和种子数据构图，因此配置归属
 * Product；Common 继续提供图谱模型、服务和抽取器实现。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(ChatModel.class)
public class ProductRagGraphConfiguration {

    @Bean
    @ConditionalOnMissingBean(KnowledgeGraphService.class)
    public KnowledgeGraphService productKnowledgeGraphService(
            ChatModel chatModel,
            AiChatService aiChatService) {
        KnowledgeGraphService graphService = new KnowledgeGraphService();
        graphService.setExtractor(new LlmEntityExtractor(chatModel, aiChatService));
        return graphService;
    }
}
