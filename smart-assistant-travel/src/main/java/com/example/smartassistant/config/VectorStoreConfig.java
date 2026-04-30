package com.example.smartassistant.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 向量存储配置
 * 使用 PostgreSQL pgvector 实现语义搜索
 * 注意：EmbeddingModel 由 spring-ai-alibaba-starter-dashscope 自动配置
 */
@Configuration
public class VectorStoreConfig {

    /**
     * 创建 PgVectorStore Bean
     * EmbeddingModel 由 Spring AI Alibaba Starter 自动提供
     */
    @Bean
    public PgVectorStore vectorStore(DataSource dataSource, EmbeddingModel embeddingModel) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024)  // DashScope text-embedding-v3 实际输出 1024 维
                .initializeSchema(true)  // 自动初始化表结构
                .build();
    }
}
