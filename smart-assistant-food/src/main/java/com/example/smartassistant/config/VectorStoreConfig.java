package com.example.smartassistant.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
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
