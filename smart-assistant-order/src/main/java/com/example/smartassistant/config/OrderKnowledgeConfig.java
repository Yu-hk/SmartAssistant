/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import com.example.smartassistant.common.rag.KnowledgeSeedData;
import com.example.smartassistant.common.rag.PgVectorKnowledgeBase;
import com.example.smartassistant.common.rag.retrieval.CrossDocumentConflictResolver;
import com.example.smartassistant.common.rag.store.KnowledgeIndexMetaService;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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

    /**
     * 订单知识库的生产主库：PostgreSQL + pgvector。
     *
     * <p>这里不再自动回落到内存。数据库或嵌入服务不可用时应启动失败并暴露部署问题，
     * 避免摄取接口表面成功、容器重启后知识却全部丢失。</p>
     */
    @Bean
    @Primary
    public PgVectorKnowledgeBase orderKnowledgeBase(
            @Qualifier("embeddingClient") EmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate,
            ChineseTokenizer tokenizer,
            ObjectProvider<KnowledgeIndexMetaService> indexMetaProvider,
            ObjectProvider<CrossDocumentConflictResolver> conflictResolverProvider) {
        log.info("[OrderKnowledge] 初始化订单知识库 (PostgreSQL + pgvector), embeddingDim={}",
                embeddingModel.dimensions());
        PgVectorKnowledgeBase kb = new PgVectorKnowledgeBase(
                KnowledgeSeedData.ORDER_KB,
                embeddingModel,
                jdbcTemplate,
                tokenizer,
                indexMetaProvider.getIfAvailable());

        CrossDocumentConflictResolver resolver = conflictResolverProvider.getIfAvailable();
        if (resolver != null) {
            kb.setConflictResolver(resolver);
        }

        // 固定 ID + upsert，启动时同步种子数据是幂等操作。
        kb.addDocuments(KnowledgeSeedData.orderDocuments());
        log.info("[OrderKnowledge] pgvector 知识库就绪: {} 篇文档, 冲突消解={}",
                kb.size(), resolver != null ? "已接线" : "未接线");
        return kb;
    }

    @Bean
    public KnowledgeRetrievalService orderKnowledgeRetrievalService(
            @Qualifier("orderKnowledgeBase") KnowledgeBase orderKnowledgeBase) {
        return new KnowledgeRetrievalService().register(orderKnowledgeBase);
    }
}
