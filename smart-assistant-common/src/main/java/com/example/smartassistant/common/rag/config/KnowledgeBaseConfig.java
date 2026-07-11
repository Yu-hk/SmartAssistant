/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.config;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.rag.BgeReranker;
import com.example.smartassistant.common.rag.InMemoryKnowledgeBase;
import com.example.smartassistant.common.rag.Reranker;
import com.example.smartassistant.common.rag.retrieval.CrossDocumentConflictResolver;
import com.example.smartassistant.common.rag.trace.RetrievalTraceRepository;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ⭐ 知识库 Spring Bean 配置 — 将 InMemoryKnowledgeBase 纳入 Spring 管理。
 * <p>
 * 自动注入：BgeEmbeddingModel、ChineseTokenizer、BgeReranker、RetrievalTraceRepository，
 * 并自动将 RetrievalTrace 写入 Redis。
 * </p>
 */
@Configuration
public class KnowledgeBaseConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseConfig.class);

    @Value("${knowledge-base.name:default}")
    private String kbName;

    @Bean
    public Reranker bgeReranker(BgeEmbeddingModel embeddingModel) {
        log.info("[KBConfig] 创建 BGE Reranker");
        return new BgeReranker(embeddingModel);
    }

    @Bean
    public RetrievalTraceRepository retrievalTraceRepository(StringRedisTemplate redisTemplate) {
        return new RetrievalTraceRepository(redisTemplate);
    }

    @Bean
    public CrossDocumentConflictResolver crossDocumentConflictResolver() {
        log.info("[KBConfig] 创建跨文档冲突消解器（Q6 第二层）");
        return new CrossDocumentConflictResolver();
    }

    @Bean
    public InMemoryKnowledgeBase inMemoryKnowledgeBase(
            BgeEmbeddingModel embeddingModel,
            ChineseTokenizer tokenizer,
            Reranker bgeReranker,
            RetrievalTraceRepository traceRepository,
            CrossDocumentConflictResolver conflictResolver) {
        log.info("[KBConfig] 创建 InMemoryKnowledgeBase: name={}", kbName);

        InMemoryKnowledgeBase kb = new InMemoryKnowledgeBase(kbName, embeddingModel, tokenizer, bgeReranker);

        // ⭐ 自动接线：检索链路追溯 → Redis 存储
        kb.setTraceConsumer(trace -> {
            if (trace != null && trace.getRequestId() != null) {
                traceRepository.save(trace);
            }
        });

        // ⭐ 自动接线：检索侧跨文档冲突消解（Q6 第二层）
        kb.setConflictResolver(conflictResolver);

        log.info("[KBConfig] InMemoryKnowledgeBase 创建完成，Reranker={}, Trace=已接线, ConflictResolver=已接线",
                bgeReranker.getClass().getSimpleName());
        return kb;
    }
}
