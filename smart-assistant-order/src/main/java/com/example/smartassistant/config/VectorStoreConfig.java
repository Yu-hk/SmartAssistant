/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.embedding.EmbeddingModel;
import com.example.smartassistant.common.vectorstore.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 向量存储配置
 * 使用 PostgreSQL pgvector 实现语义搜索
 */
@Configuration
public class VectorStoreConfig {

    /**
     * 创建 PgVectorStore Bean
     */
    @Bean
    public PgVectorStore vectorStore(DataSource dataSource, EmbeddingModel embeddingModel) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024)
                .initializeSchema(true)
                .build();
    }
}
