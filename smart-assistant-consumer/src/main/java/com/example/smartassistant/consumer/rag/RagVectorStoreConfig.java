/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * RAG 向量存储配置
 * 使用 PostgreSQL pgvector 实现持久化向量存储
 * 支持语义搜索、相似性检索等功能
 */
@Slf4j
@Configuration
public class RagVectorStoreConfig {
    
    /**
     * 创建 PgVectorStore Bean
     * 使用 PostgreSQL + pgvector 扩展实现向量存储
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.rag.vector-store", name = "type",
            havingValue = "pgvector", matchIfMissing = true)
    public PgVectorStore vectorStore(DataSource dataSource, EmbeddingModel embeddingModel) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        
        log.info("[RAG] 初始化 PgVectorStore...");
        
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024)  // DashScope text-embedding-v4 输出 1024 维
                .initializeSchema(true)  // 自动初始化表结构
                .build();
    }

    /**
     * 测试环境使用内存向量库，避免普通 Web 集成测试依赖外部 PostgreSQL。
     * 真实 PGVector 行为由 PgVectorKnowledgeBaseIntegrationTest 单独验证。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.rag.vector-store", name = "type", havingValue = "simple")
    public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        log.info("[RAG] 初始化测试用 SimpleVectorStore...");
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
