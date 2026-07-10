/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.config;

import com.example.smartassistant.common.rag.ingestion.KnowledgeIngestionService;
import com.example.smartassistant.consumer.service.cache.AnswerCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * ⭐ 缓存失效接线——文章⑦「RAG 索引重建与向量库同步」的落地项。
 *
 * <p>知识库成功入库/更新后，{@link KnowledgeIngestionService} 会触发缓存失效钩子。
 * 本配置将 {@link AnswerCacheService#invalidateAll()} 注册为该钩子，
 * 确保知识库变更后旧的答案缓存（L1 Redis + L2 向量检索）立即失效，不再返回陈旧回答。</p>
 *
 * <p>放在 consumer 模块是因为 {@code AnswerCacheService} 在此、且 {@code KnowledgeIngestionService}
 * 由 common 的 {@code IngestionJobAutoConfiguration} 以 {@code @Bean} 暴露，跨模块通过 Bean 注入接线。</p>
 */
@Configuration
public class CacheInvalidationWiringConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationWiringConfig.class);

    public CacheInvalidationWiringConfig(KnowledgeIngestionService knowledgeIngestionService,
                                         AnswerCacheService answerCacheService) {
        knowledgeIngestionService.addCacheInvalidationHook(() -> {
            try {
                answerCacheService.invalidateAll().block();
                log.info("[CacheInvalidation] 知识库更新 → 答案缓存已失效");
            } catch (Exception e) {
                log.warn("[CacheInvalidation] 答案缓存失效钩子异常（已忽略）: {}", e.getMessage());
            }
        });
        log.info("[CacheInvalidation] 已注册 AnswerCacheService 为 KnowledgeIngestionService 的缓存失效钩子");
    }
}
