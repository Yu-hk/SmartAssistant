/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.config;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.rag.BgeReranker;
import com.example.smartassistant.common.rag.Bm25Scorer;
import com.example.smartassistant.common.rag.InMemoryKnowledgeBase;
import com.example.smartassistant.common.rag.Reranker;
import com.example.smartassistant.common.rag.retrieval.CrossDocumentConflictResolver;
import com.example.smartassistant.common.rag.trace.RetrievalTraceRepository;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(BgeEmbeddingModel.class)
    @ConditionalOnProperty(name = "embedding.local.enabled", havingValue = "true")
    public BgeEmbeddingModel bgeEmbeddingModel(
            @Value("${BGE_MODEL_PATH:models/bge-small-zh-v1.5.onnx}") String modelPath,
            @Value("${BGE_VOCAB_PATH:models/tokenizer.json}") String vocabPath) {
        log.info("[KBConfig] 创建可降级 BGE 模型: modelPath={}", modelPath);
        return new BgeEmbeddingModel(modelPath, vocabPath);
    }

    @Bean
    public Reranker bgeReranker(BgeEmbeddingModel embeddingModel) {
        log.info("[KBConfig] 创建 BGE Reranker");
        return new BgeReranker(embeddingModel);
    }

    @Bean("commonBm25Scorer")
    @ConditionalOnMissingBean(Bm25Scorer.class)
    public Bm25Scorer bm25Scorer(ChineseTokenizer tokenizer) {
        log.info("[KBConfig] 创建 BM25 评分器");
        return new Bm25Scorer(tokenizer);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    public RetrievalTraceRepository retrievalTraceRepository(ApplicationContext applicationContext)
            throws ReflectiveOperationException {
        Class<?> templateType = Class.forName(
                "org.springframework.data.redis.core.StringRedisTemplate",
                false,
                applicationContext.getClassLoader());
        Object redisTemplate = applicationContext.getBean(templateType);
        return (RetrievalTraceRepository) RetrievalTraceRepository.class
                .getConstructor(templateType)
                .newInstance(redisTemplate);
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
            ObjectProvider<RetrievalTraceRepository> traceRepositoryProvider,
            CrossDocumentConflictResolver conflictResolver) {
        log.info("[KBConfig] 创建 InMemoryKnowledgeBase: name={}", kbName);

        InMemoryKnowledgeBase kb = new InMemoryKnowledgeBase(kbName, embeddingModel, tokenizer, bgeReranker);

        // ⭐ 自动接线：检索链路追溯 → Redis 存储
        RetrievalTraceRepository traceRepository = traceRepositoryProvider.getIfAvailable();
        if (traceRepository != null) {
            kb.setTraceConsumer(trace -> {
                if (trace != null && trace.getRequestId() != null) {
                    traceRepository.save(trace);
                }
            });
        }

        // ⭐ 自动接线：检索侧跨文档冲突消解（Q6 第二层）
        kb.setConflictResolver(conflictResolver);

        log.info("[KBConfig] InMemoryKnowledgeBase 创建完成，Reranker={}, Trace=已接线, ConflictResolver=已接线",
                bgeReranker.getClass().getSimpleName());
        return kb;
    }
}
