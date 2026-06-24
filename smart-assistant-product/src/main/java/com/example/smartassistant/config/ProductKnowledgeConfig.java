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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 产品知识库配置——初始化基于 BGE 嵌入的知识库。
 */
@Configuration
public class ProductKnowledgeConfig {

    private static final Logger log = LoggerFactory.getLogger(ProductKnowledgeConfig.class);

    @Value("${bge.model.path:models/bge-large-zh-v1.5.onnx}")
    private String modelPath;

    @Value("${bge.vocab.path:models/tokenizer.json}")
    private String vocabPath;

    @Bean
    @ConditionalOnMissingBean
    public BgeEmbeddingModel productBgeEmbeddingModel() {
        log.info("[ProductKnowledge] 加载 BGE 模型 (path={})", modelPath);
        return new BgeEmbeddingModel(modelPath, vocabPath);
    }

    @Bean
    public InMemoryKnowledgeBase productKnowledgeBase(BgeEmbeddingModel productBgeEmbeddingModel) {
        log.info("[ProductKnowledge] 初始化产品知识库...");
        InMemoryKnowledgeBase kb = KnowledgeSeedData.createProductKnowledgeBase(productBgeEmbeddingModel);
        log.info("[ProductKnowledge] 产品知识库就绪: {} 篇文档", kb.size());
        return kb;
    }

    @Bean
    public KnowledgeRetrievalService productKnowledgeRetrievalService(InMemoryKnowledgeBase productKnowledgeBase) {
        return new KnowledgeRetrievalService()
                .register(productKnowledgeBase);
    }
}
