/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.rag;

import com.example.smartassistant.common.embedding.EmbeddingModel;
import com.example.smartassistant.common.vectorstore.PgVectorStore;
import lombok.extern.slf4j.Slf4j;
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
    public PgVectorStore vectorStore(DataSource dataSource, EmbeddingModel embeddingModel) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        log.info("[RAG] 初始化 PgVectorStore...");

        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024)
                .initializeSchema(true)
                .build();
    }
}
