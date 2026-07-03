/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.rag.BgeReranker;
import com.example.smartassistant.common.rag.InMemoryKnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import com.example.smartassistant.common.rag.KnowledgeSeedData;
import com.example.smartassistant.common.rag.MilvusKnowledgeBase;
import com.example.smartassistant.common.rag.Reranker;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 订单知识库配置——BGE + BM25 + pgvector 持久化。
 */
@Configuration
public class OrderKnowledgeConfig {

    private static final Logger log = LoggerFactory.getLogger(OrderKnowledgeConfig.class);

    /** ⭐ bge-reranker Cross-Encoder（改为注入 BgeEmbeddingModel，替换旧有 model+vocab 文件路径） */
    @Bean
    @ConditionalOnProperty(name = "reranker.enabled", havingValue = "true")
    public Reranker orderReranker(BgeEmbeddingModel embeddingModel) {
        if (embeddingModel == null || !embeddingModel.isAvailable()) {
            log.warn("[OrderKnowledge] BGE 嵌入模型不可用，Reranker 降级为恒等映射");
            return Reranker.identity();
        }
        log.info("[OrderKnowledge] 初始化 BgeReranker: using BgeEmbeddingModel");
        BgeReranker reranker = new BgeReranker(embeddingModel);
        log.info("[OrderKnowledge] BgeReranker 就绪");
        return reranker;
    }

    /** 内存知识库（轻量，默认启用） */
    @Bean
    @Primary
    public InMemoryKnowledgeBase orderKnowledgeBase(
            BgeEmbeddingModel embeddingModel,
            ChineseTokenizer tokenizer,
            ObjectProvider<Reranker> rerankerProvider) {
        Reranker reranker = rerankerProvider.getIfAvailable();
        log.info("[OrderKnowledge] 初始化订单知识库 (InMemory + BGE + BM25), Reranker={}",
                reranker != null && reranker != Reranker.identity() ? "已启用" : "未启用");
        InMemoryKnowledgeBase kb = KnowledgeSeedData.createOrderKnowledgeBase(
                embeddingModel, tokenizer, reranker);
        log.info("[OrderKnowledge] 订单知识库就绪: {} 篇文档", kb.size());
        return kb;
    }

    /** Milvus 持久化知识库（按配置启用，替换 pgvector） */
    @Bean
    @ConditionalOnProperty(name = "app.milvus.enabled", havingValue = "true")
    public MilvusKnowledgeBase orderMilvusKnowledgeBase(
            BgeEmbeddingModel embeddingModel,
            ChineseTokenizer tokenizer,
            @Value("${app.milvus.host:localhost}") String milvusHost,
            @Value("${app.milvus.port:19530}") int milvusPort) {
        log.info("[OrderKnowledge] 初始化 Milvus 持久化知识库: {}:{}", milvusHost, milvusPort);
        MilvusKnowledgeBase kb = new MilvusKnowledgeBase(
                KnowledgeSeedData.ORDER_KB, embeddingModel, milvusHost, milvusPort, tokenizer);
        log.info("[OrderKnowledge] Milvus 知识库就绪");
        return kb;
    }

    @Bean
    public KnowledgeRetrievalService orderKnowledgeRetrievalService(
            InMemoryKnowledgeBase orderKnowledgeBase,
            ObjectProvider<MilvusKnowledgeBase> milvusProvider) {
        KnowledgeRetrievalService service = new KnowledgeRetrievalService()
                .register(orderKnowledgeBase);
        MilvusKnowledgeBase milvusKb = milvusProvider.getIfAvailable();
        if (milvusKb != null) {
            service.register(milvusKb);
            log.info("[OrderKnowledge] Milvus 知识库已注册到检索服务");
        }
        return service;
    }
}
