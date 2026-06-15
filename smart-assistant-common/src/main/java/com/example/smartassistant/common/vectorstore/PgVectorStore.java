/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.vectorstore;

import com.example.smartassistant.common.document.Document;
import com.example.smartassistant.common.embedding.EmbeddingModel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PostgreSQL pgvector 向量存储实现，替代 Spring AI 1.x 的
 * {@code org.springframework.ai.vectorstore.pgvector.PgVectorStore}。
 *
 * <p>使用 JDBC 直接操作 pgvector 扩展，兼容 Spring AI 1.x 创建的
 * {@code vector_store} 表结构。</p>
 *
 * <p>依赖：PostgreSQL 需安装 {@code pgvector} 扩展。</p>
 */
public class PgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    /** 默认表名，与 Spring AI 1.x PgVectorStore 保持一致。 */
    public static final String DEFAULT_TABLE_NAME = "vector_store";

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final int dimensions;
    private final String tableName;
    private final boolean initializeSchema;
    private final ObjectMapper objectMapper;

    private PgVectorStore(Builder builder) {
        this.jdbcTemplate = builder.jdbcTemplate;
        this.embeddingModel = builder.embeddingModel;
        this.dimensions = builder.dimensions;
        this.tableName = builder.tableName != null ? builder.tableName : DEFAULT_TABLE_NAME;
        this.initializeSchema = builder.initializeSchema;
        this.objectMapper = new ObjectMapper();

        if (initializeSchema) {
            createTableIfNotExists();
        }
    }

    // ---- Builder ----

    public static Builder builder(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return new Builder(jdbcTemplate, embeddingModel);
    }

    public static class Builder {
        private final JdbcTemplate jdbcTemplate;
        private final EmbeddingModel embeddingModel;
        private int dimensions = 1024;
        private String tableName;
        private boolean initializeSchema = false;

        Builder(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
            this.jdbcTemplate = jdbcTemplate;
            this.embeddingModel = embeddingModel;
        }

        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public Builder initializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
            return this;
        }

        public PgVectorStore build() {
            return new PgVectorStore(this);
        }
    }

    // ---- Schema ----

    /**
     * 创建 pgvector 扩展和向量存储表（如果不存在）。
     * <p>与 Spring AI 1.x 的 {@code PgVectorStore} 兼容。</p>
     */
    private void createTableIfNotExists() {
        try {
            // 创建 pgvector 扩展
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");

            // 创建向量存储表
            String sql = String.format("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id UUID PRIMARY KEY,
                        content TEXT,
                        metadata JSONB DEFAULT '{}'::jsonb,
                        embedding VECTOR(%d)
                    )
                    """, tableName, dimensions);

            jdbcTemplate.execute(sql);

            // 创建索引（IVFFlat 索引，list 数量根据数据量调整）
            String indexSql = String.format("""
                    CREATE INDEX IF NOT EXISTS idx_%s_embedding
                        ON %s USING ivfflat (embedding vector_cosine_ops)
                        WITH (lists = 100)
                    """, tableName, tableName);

            try {
                jdbcTemplate.execute(indexSql);
            } catch (Exception e) {
                log.debug("[PgVectorStore] 索引创建跳过（可能已存在或 pgvector 版本较低）: {}", e.getMessage());
            }

            log.info("[PgVectorStore] 表 '{}' 已就绪（维度={}）", tableName, dimensions);
        } catch (Exception e) {
            log.error("[PgVectorStore] 初始化失败: {}", e.getMessage(), e);
            throw new RuntimeException("pgvector 初始化失败，请确保 PostgreSQL 已安装 pgvector 扩展", e);
        }
    }

    // ---- VectorStore implementation ----

    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        String sql = String.format("""
                INSERT INTO %s (id, content, metadata, embedding)
                VALUES (?, ?, ?::jsonb, ?::vector)
                ON CONFLICT (id) DO UPDATE SET
                    content = EXCLUDED.content,
                    metadata = EXCLUDED.metadata,
                    embedding = EXCLUDED.embedding
                """, tableName);

        for (Document doc : documents) {
            try {
                float[] embedding = embeddingModel.embed(doc);
                String metadataJson = objectMapper.writeValueAsString(doc.getMetadata());
                String embeddingStr = vectorToString(embedding);

                jdbcTemplate.update(sql,
                        UUID.fromString(doc.getId()),
                        doc.getText(),
                        metadataJson,
                        embeddingStr);
            } catch (Exception e) {
                log.error("[PgVectorStore] 添加文档失败: id={}, error={}", doc.getId(), e.getMessage());
            }
        }

        log.debug("[PgVectorStore] 已添加 {} 个文档", documents.size());
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        String queryEmbedding = vectorToString(embeddingModel.embed(request.getQuery()));

        // 构建带过滤条件的 SQL
        String filterClause = buildFilterClause(request.getFilterExpression());

        // 使用余弦距离（<=>），距离越小越相似
        String sql = String.format("""
                SELECT id, content, metadata, embedding,
                       1 - (embedding <=> ?::vector) AS similarity
                FROM %s
                %s
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """, tableName, filterClause);

        double threshold = request.getSimilarityThreshold();

        List<Document> results = jdbcTemplate.query(
                sql,
                (ResultSet rs, int rowNum) -> {
                    String docId = rs.getString("id");
                    String content = rs.getString("content");
                    double similarity = rs.getDouble("similarity");

                    // 解析 metadata JSON → Map
                    Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));

                    Document doc = new Document(docId, content, metadata);
                    doc.setScore(similarity);
                    return doc;
                },
                queryEmbedding,   // 第一个 ? (SELECT 中的)
                queryEmbedding,   // 第二个 ? (ORDER BY 中的)
                request.getTopK() // LIMIT
        );

        // 应用相似度阈值过滤
        return results.stream()
                .filter(doc -> doc.getScore() != null && doc.getScore() >= threshold)
                .collect(Collectors.toList());
    }

    // ---- Helpers ----

    /**
     * 将 float[] 转换为 pgvector 格式的字符串（如 [0.1,0.2,0.3]）。
     */
    private String vectorToString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 解析 metadata JSON 字符串。
     */
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isEmpty() || json.equals("{}")) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("[PgVectorStore] metadata 解析失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 构建过滤子句。
     * <p>注意：此实现仅支持简单的 {@code key == 'value' && key == 'value'} 表达式。
     * 复杂的过滤条件需要根据实际需求扩展。</p>
     */
    private String buildFilterClause(String filterExpression) {
        if (filterExpression == null || filterExpression.trim().isEmpty()) {
            return "";
        }

        // 将 "key == 'value' && key2 == 'value2'" 转换为 PostgreSQL JSONB 查询
        StringBuilder whereClause = new StringBuilder(" WHERE ");
        String[] conditions = filterExpression.split("&&");

        for (int i = 0; i < conditions.length; i++) {
            String condition = conditions[i].trim();
            // 解析 key == 'value'
            String[] parts = condition.split("==");
            if (parts.length == 2) {
                String key = parts[0].trim();
                String value = parts[1].trim().replaceAll("^'|'$", ""); // 去引号
                if (i > 0) whereClause.append(" AND ");
                whereClause.append("metadata->>'").append(key).append("' = '").append(value).append("'");
            }
        }

        return whereClause.toString();
    }
}
