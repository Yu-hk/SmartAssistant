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
import com.example.smartassistant.common.rag.PgVectorKnowledgeBase;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 订单知识库配置——BGE + BM25 + pgvector 持久化。
 */
@Configuration
public class OrderKnowledgeConfig {

    private static final Logger log = LoggerFactory.getLogger(OrderKnowledgeConfig.class);

    /** 内存知识库（轻量，默认启用） */
    @Bean
    @Primary
    public InMemoryKnowledgeBase orderKnowledgeBase(BgeEmbeddingModel embeddingModel,
                                                     ChineseTokenizer tokenizer) {
        log.info("[OrderKnowledge] 初始化订单知识库 (InMemory + BGE + BM25)...");
        InMemoryKnowledgeBase kb = KnowledgeSeedData.createOrderKnowledgeBase(embeddingModel, tokenizer);
        log.info("[OrderKnowledge] 订单知识库就绪: {} 篇文档, BM25={}",
                kb.size(), tokenizer != null ? "已启用" : "未启用");
        return kb;
    }

    /** pgvector 持久化知识库（按配置启用） */
    @Bean
    @ConditionalOnProperty(name = "app.knowledge.pgvector-enabled", havingValue = "true")
    public PgVectorKnowledgeBase orderPgVectorKnowledgeBase(
            BgeEmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate,
            ChineseTokenizer tokenizer) {
        log.info("[OrderKnowledge] 初始化 pgvector 持久化知识库...");
        PgVectorKnowledgeBase kb = new PgVectorKnowledgeBase(
                KnowledgeSeedData.ORDER_KB, embeddingModel, jdbcTemplate, tokenizer);
        log.info("[OrderKnowledge] pgvector 知识库就绪");
        return kb;
    }

    @Bean
    public KnowledgeRetrievalService orderKnowledgeRetrievalService(
            InMemoryKnowledgeBase orderKnowledgeBase,
            ObjectProvider<PgVectorKnowledgeBase> pgVectorProvider) {
        KnowledgeRetrievalService service = new KnowledgeRetrievalService()
                .register(orderKnowledgeBase);
        PgVectorKnowledgeBase pgKb = pgVectorProvider.getIfAvailable();
        if (pgKb != null) {
            service.register(pgKb);
        }
        return service;
    }
}
