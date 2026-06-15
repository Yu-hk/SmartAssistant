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

/**
 * PgVector 向量存储配置
 */
@Configuration
public class VectorStoreConfig {

    /**
     * 创建 PgVectorStore Bean
     */
    @Bean
    public PgVectorStore vectorStore(JdbcTemplate jdbcTemplate,
                                      EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .build();
    }
}
