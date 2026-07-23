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
import com.example.smartassistant.common.rag.document.mineru.MinerUClient;
import com.example.smartassistant.common.rag.document.mineru.MinerUProperties;
import com.example.smartassistant.common.rag.graph.KnowledgeGraphService;
import com.example.smartassistant.common.rag.ingestion.DocumentMetadataEnricher;
import com.example.smartassistant.common.rag.ingestion.DocumentValidator;
import com.example.smartassistant.common.rag.ingestion.KnowledgeIngestionService;
import com.example.smartassistant.common.rag.ingestion.ReviewQueueService;
import com.example.smartassistant.common.rag.properties.RagProductionProperties;
import com.example.smartassistant.common.rag.store.KnowledgeIndexMetaService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 异步摄取任务管线自动装配——仅在有 {@link KnowledgeBase} Bean 的 Web 应用上下文激活。
 *
 * <p>自包含创建 {@link KnowledgeIngestionService}（依赖解析器/分块器/知识库），并注入
 * RAG 生产化改造的治理组件（元数据绑定器 / 脏数据校验器 / 复核队列 / 索引版本元数据），
 * 从而无需各业务模块手动接线，降低接入成本。所有 Bean 均带
 * {@link ConditionalOnMissingBean}，允许业务方自定义覆盖。</p>
 */
@Configuration
@ConditionalOnWebApplication
public class IngestionJobAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DocumentParseRouter ingestionDocumentParseRouter(
            ObjectProvider<MinerUProperties> minerUPropertiesProvider,
            ObjectProvider<MinerUClient> minerUClientProvider) {
        // 未启用 MinerU（或 sidecar Bean 不可用）时，两 provider 均返回 null → 纯 PDFBox 行为
        return new DocumentParseRouter(
                minerUPropertiesProvider.getIfAvailable(),
                minerUClientProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentChunker ingestionDocumentChunker() {
        return new DocumentChunker();
    }

    /** 元数据绑定器（REQ-1，治理组件） */
    @Bean
    @ConditionalOnMissingBean
    public DocumentMetadataEnricher documentMetadataEnricher() {
        return new DocumentMetadataEnricher();
    }

    /** 脏数据校验器（REQ-1，治理组件） */
    @Bean
    @ConditionalOnMissingBean
    public DocumentValidator documentValidator() {
        return new DocumentValidator();
    }

    /** 复核队列服务（REQ-1，有 JdbcTemplate 则落 PG，否则内存） */
    @Bean
    @ConditionalOnMissingBean
    public ReviewQueueService reviewQueueService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        return new ReviewQueueService(jdbcTemplateProvider.getIfAvailable());
    }

    /** 索引版本元数据服务（REQ-2，有 JdbcTemplate 则落 PG，否则内存） */
    @Bean
    @ConditionalOnMissingBean
    public KnowledgeIndexMetaService knowledgeIndexMetaService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        return new KnowledgeIndexMetaService(jdbcTemplateProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeIngestionService knowledgeIngestionService(DocumentParseRouter router,
                                                               DocumentChunker chunker,
                                                               KnowledgeBase knowledgeBase,
                                                               ObjectProvider<KnowledgeGraphService> graphProvider,
                                                               DocumentMetadataEnricher enricher,
                                                               DocumentValidator validator,
                                                               ReviewQueueService reviewQueueService,
                                                               KnowledgeIndexMetaService indexMetaService) {
        KnowledgeIngestionService service = new KnowledgeIngestionService(router, chunker, knowledgeBase);
        KnowledgeGraphService graph = graphProvider.getIfAvailable();
        if (graph != null) {
            service.setKnowledgeGraphService(graph);
        }
        // ⭐ RAG 生产化：注入治理组件（元数据绑定 / 脏数据校验 / 复核队列 / 索引版本）
        service.setMetadataEnricher(enricher);
        service.setValidator(validator);
        service.setReviewQueueService(reviewQueueService);
        service.setIndexMetaService(indexMetaService);
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

    /** Webhook 触发端点（对象存储事件 → 提交） */
    @Bean
    @ConditionalOnMissingBean
    public IngestionWebhookController ingestionWebhookController(IngestionJobManager manager) {
        return new IngestionWebhookController(manager);
    }

    /** 复核队列 REST 端点（脏数据审批） */
    @Bean
    @ConditionalOnMissingBean
    public ReviewQueueController reviewQueueController(ReviewQueueService reviewQueueService) {
        return new ReviewQueueController(reviewQueueService);
    }

    /**
     * 定时扫描摄入轮询器（兜底扫描目录）。
     * <p>始终注册；实际是否扫描由 {@code app.rag.ingest.trigger} 是否含 {@code schedule} 与
     * {@code app.rag.ingest.scan-dir} 是否在运行时决定（轮询器内部自判断，避免空扫描）。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public ScheduledIngestionPoller scheduledIngestionPoller(IngestionJobManager manager,
                                                             RagProductionProperties properties) {
        return new ScheduledIngestionPoller(manager, properties);
    }
}
