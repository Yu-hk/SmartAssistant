/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.rag.InMemoryKnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import com.example.smartassistant.common.rag.KnowledgeSeedData;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 订单知识库配置——初始化基于 BGE 嵌入的知识库。
 */
@Configuration
public class OrderKnowledgeConfig {

    private static final Logger log = LoggerFactory.getLogger(OrderKnowledgeConfig.class);

    @Bean
    public InMemoryKnowledgeBase orderKnowledgeBase(BgeEmbeddingModel embeddingModel) {
        log.info("[OrderKnowledge] 初始化订单知识库...");
        InMemoryKnowledgeBase kb = KnowledgeSeedData.createOrderKnowledgeBase(embeddingModel);
        log.info("[OrderKnowledge] 订单知识库就绪: {} 篇文档", kb.size());
        return kb;
    }

    @Bean
    public KnowledgeRetrievalService orderKnowledgeRetrievalService(InMemoryKnowledgeBase orderKnowledgeBase) {
        return new KnowledgeRetrievalService()
                .register(orderKnowledgeBase);
    }
}
