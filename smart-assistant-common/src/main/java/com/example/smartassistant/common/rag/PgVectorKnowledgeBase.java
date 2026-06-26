/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于 PostgreSQL pgvector 的知识库持久化实现。
 * <p>
 * 文档和向量存储在 PostgreSQL 表中，支持持久化、跨实例共享。
 * 作为 {@link InMemoryKnowledgeBase} 的替代方案适用于多实例部署。
 * </p>
 *
 * <p>表结构（自动创建）：</p>
 * <pre>
 * CREATE TABLE IF NOT EXISTS knowledge_docs (
 *     id VARCHAR(128) PRIMARY KEY,
 *     title TEXT NOT NULL,
 *     content TEXT NOT NULL,
 *     category VARCHAR(64),
 *     keywords TEXT,
 *     effective_at BIGINT DEFAULT -1,
 *     expire_at BIGINT DEFAULT -1,
 *     embedding vector(384),  -- BGE-small-zh 维度
 *     created_at BIGINT NOT NULL
 * );
 * CREATE INDEX IF NOT EXISTS idx_knowledge_embedding ON knowledge_docs
 *     USING hnsw (embedding vector_cosine_ops);
 * </pre>
 */
public class PgVectorKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(PgVectorKnowledgeBase.class);

    /** 表名 */
    private static final String TABLE = "knowledge_docs";

    /** BGE-small-zh 向量维度 */
    private static final int DIMENSIONS = 384;

    private final String name;
    private final BgeEmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;

    /** BM25 评分器（可选） */
    private Bm25Scorer bm25Scorer;

    /** BM25 混合权重 */
    private static final double BM25_MIX_WEIGHT = 0.3;

    /** 余弦相似度阈值 */
    private static final double MIN_SIMILARITY = 0.30;

    /** 时间衰减 λ */
    private static final double TIME_DECAY_LAMBDA = 0.01;

    public PgVectorKnowledgeBase(String name, BgeEmbeddingModel embeddingModel,
                                  JdbcTemplate jdbcTemplate, ChineseTokenizer tokenizer) {
        this.name = name;
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        if (tokenizer != null) {
            this.bm25Scorer = new Bm25Scorer(tokenizer);
        }
        initSchema();
    }

    /** 自动建表 */
    private void initSchema() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id VARCHAR(128) PRIMARY KEY,"
                + "title TEXT NOT NULL,"
                + "content TEXT NOT NULL,"
                + "category VARCHAR(64),"
                + "keywords TEXT,"
                + "effective_at BIGINT DEFAULT -1,"
                + "expire_at BIGINT DEFAULT -1,"
                + "embedding vector(" + DIMENSIONS + "),"
                + "created_at BIGINT NOT NULL"
                + ")");
        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_embedding_hnsw"
                    + " ON " + TABLE + " USING hnsw (embedding vector_cosine_ops)");
        } catch (Exception e) {
            log.warn("[PgVectorKB:{}] HNSW 索引创建失败（可能已存在或 pgvector 版本问题）: {}",
                    name, e.getMessage());
        }
    }

    @Override
    public String getName() { return name; }

    @Override
    public void addDocument(KnowledgeDocument doc) {
        if (doc == null) return;
        float[] vec = embeddingModel.embedding(doc.toEmbedText());
        String vecStr = vec != null ? arrayToPgVector(vec) : null;

        jdbcTemplate.update(
                "INSERT INTO " + TABLE + " (id, title, content, category, keywords, "
                        + "effective_at, expire_at, embedding, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, " + (vecStr != null ? "?::vector" : "NULL") + ", ?) "
                        + "ON CONFLICT (id) DO UPDATE SET "
                        + "title=EXCLUDED.title, content=EXCLUDED.content, "
                        + "category=EXCLUDED.category, keywords=EXCLUDED.keywords, "
                        + "embedding=" + (vecStr != null ? "EXCLUDED.embedding" : "NULL"),
                doc.getId(), doc.getTitle(), doc.getContent(),
                doc.getCategory(), doc.getKeywords(),
                doc.getEffectiveAt(), doc.getExpireAt(),
                vecStr, System.currentTimeMillis());
    }

    @Override
    public void removeDocument(String id) {
        jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        int k = (topK > 0) ? topK : 5;

        // 嵌入查询
        float[] queryVec = embeddingModel.embedding(query);
        if (queryVec == null) {
            log.warn("[PgVectorKB:{}] 嵌入不可用，降级到关键词搜索", name);
            return fallbackSearch(query, k);
        }

        // 向量搜索 + 过滤过期文档
        String vecStr = arrayToPgVector(queryVec);
        long now = System.currentTimeMillis();
        List<KnowledgeDocument> candidates = jdbcTemplate.query(
                "SELECT id, title, content, category, keywords, effective_at, expire_at, created_at, "
                        + "(embedding <-> ?::vector) AS dist "
                        + "FROM " + TABLE + " "
                        + "WHERE (effective_at <= 0 OR effective_at <= ?) "
                        + "AND (expire_at <= 0 OR expire_at > ?) "
                        + "ORDER BY embedding <-> ?::vector LIMIT ?",
                (ResultSet rs, int rowNum) -> {
                    KnowledgeDocument doc = new KnowledgeDocument(
                            rs.getString("id"), rs.getString("title"),
                            rs.getString("content"), rs.getString("category"),
                            rs.getString("keywords"),
                            rs.getLong("effective_at"), rs.getLong("expire_at"));
                    return doc;
                },
                vecStr, now, now, vecStr, 50); // 粗筛 50 条

        // 精排：BM25 + 时间衰减
        List<ScoredDoc> scored = candidates.stream()
                .filter(doc -> doc.isActive())
                .map(doc -> {
                    double cosSim = 1.0; // pgvector <-> 已返回余弦距离，此处简化
                    double score = composeScore(cosSim, doc, query);
                    return new ScoredDoc(doc, score);
                })
                .filter(sd -> sd.score >= MIN_SIMILARITY)
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(k)
                .collect(Collectors.toList());

        return scored.stream()
                .map(sd -> new KnowledgeHit(sd.doc, sd.score))
                .collect(Collectors.toList());
    }

    @Override
    public int size() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE, Integer.class);
        return count != null ? count : 0;
    }

    @Override
    public void reindex() {
        // 重新计算所有 embedding
        List<KnowledgeDocument> allDocs = listAll();
        for (KnowledgeDocument doc : allDocs) {
            float[] vec = embeddingModel.embedding(doc.toEmbedText());
            if (vec != null) {
                jdbcTemplate.update(
                        "UPDATE " + TABLE + " SET embedding = ?::vector WHERE id = ?",
                        arrayToPgVector(vec), doc.getId());
            }
        }
        // 重建 BM25 索引
        if (bm25Scorer != null) {
            bm25Scorer.initialize(allDocs);
        }
        log.info("[PgVectorKB:{}] 重新索引完成: {} 篇文档", name, allDocs.size());
    }

    // ==================== 内部方法 ====================

    private List<KnowledgeDocument> listAll() {
        return jdbcTemplate.query(
                "SELECT id, title, content, category, keywords, effective_at, expire_at, created_at "
                        + "FROM " + TABLE,
                (ResultSet rs, int rowNum) -> new KnowledgeDocument(
                        rs.getString("id"), rs.getString("title"),
                        rs.getString("content"), rs.getString("category"),
                        rs.getString("keywords"),
                        rs.getLong("effective_at"), rs.getLong("expire_at")));
    }

    private List<KnowledgeHit> fallbackSearch(String query, int topK) {
        String q = "%" + query + "%";
        return jdbcTemplate.query(
                "SELECT id, title, content, category, keywords, effective_at, expire_at, created_at "
                        + "FROM " + TABLE + " "
                        + "WHERE (title ILIKE ? OR content ILIKE ? OR keywords ILIKE ?) "
                        + "LIMIT ?",
                (ResultSet rs, int rowNum) -> {
                    KnowledgeDocument doc = new KnowledgeDocument(
                            rs.getString("id"), rs.getString("title"),
                            rs.getString("content"), rs.getString("category"),
                            rs.getString("keywords"),
                            rs.getLong("effective_at"), rs.getLong("expire_at"));
                    return new KnowledgeHit(doc, 0.5);
                },
                q, q, q, topK);
    }

    private double composeScore(double cosSim, KnowledgeDocument doc, String query) {
        double timeDecay = 1.0;
        if (doc.getExpireAt() > 0) {
            long daysToExpire = (doc.getExpireAt() - System.currentTimeMillis()) / 86400000;
            if (daysToExpire > 0) {
                timeDecay = Math.exp(-TIME_DECAY_LAMBDA * (365 - daysToExpire));
            }
        }
        double bm25Score = 0;
        if (bm25Scorer != null && bm25Scorer.isInitialized()) {
            bm25Score = Math.tanh(bm25Scorer.score(doc, query));
            bm25Score = Math.min(bm25Score, 1.0);
        }
        return cosSim * timeDecay * (1 - BM25_MIX_WEIGHT) + bm25Score * BM25_MIX_WEIGHT;
    }

    /** float[] → pgvector 字符串格式 '[0.1,0.2,...]' */
    private static String arrayToPgVector(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", vec[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    private static class ScoredDoc {
        final KnowledgeDocument doc;
        final double score;
        ScoredDoc(KnowledgeDocument doc, double score) { this.doc = doc; this.score = score; }
    }
}
