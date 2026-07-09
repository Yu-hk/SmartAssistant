/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.chunking.DocumentChunker;
import com.example.smartassistant.common.rag.document.DocumentParseRouter;
import com.example.smartassistant.common.rag.graph.KnowledgeGraphService;
import com.example.smartassistant.common.rag.ingestion.KnowledgeIngestionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 异步摄取任务管线自动装配——仅在有 {@link KnowledgeBase} Bean 的 Web 应用上下文激活。
 *
 * <p>自包含创建 {@link KnowledgeIngestionService}（依赖解析器/分块器/知识库），
 * 从而无需各业务模块手动接线，降低接入成本。所有 Bean 均带
 * {@link ConditionalOnMissingBean}，允许业务方自定义覆盖。</p>
 */
@Configuration
@ConditionalOnWebApplication
public class IngestionJobAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DocumentParseRouter ingestionDocumentParseRouter() {
        return new DocumentParseRouter();
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentChunker ingestionDocumentChunker() {
        return new DocumentChunker();
    }

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeIngestionService knowledgeIngestionService(DocumentParseRouter router,
                                                               DocumentChunker chunker,
                                                               KnowledgeBase knowledgeBase,
                                                               ObjectProvider<KnowledgeGraphService> graphProvider) {
        KnowledgeIngestionService service = new KnowledgeIngestionService(router, chunker, knowledgeBase);
        KnowledgeGraphService graph = graphProvider.getIfAvailable();
        if (graph != null) {
            service.setKnowledgeGraphService(graph);
        }
        return service;
    }

    @Bean
    @ConditionalOnMissingBean
    public IngestionJobRepository ingestionJobRepository() {
        return new InMemoryIngestionJobRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public IngestionJobManager ingestionJobManager(KnowledgeIngestionService ingestion,
                                                   IngestionJobRepository repo) {
        return new IngestionJobManager(ingestion, repo);
    }

    @Bean
    public IngestionJobController ingestionJobController(IngestionJobManager manager) {
        return new IngestionJobController(manager);
    }
}
