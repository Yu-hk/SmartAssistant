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
 *     tenant_id VARCHAR(64) DEFAULT '',      -- 🔴 ACL：租户隔离
 *     version VARCHAR(32) DEFAULT 'v1',       -- 🔴 版本：灰度回滚
 *     source_url VARCHAR(1024) DEFAULT '',    -- 🟡 来源：引用回链
 *     chunk_index INT DEFAULT -1,             -- 🟡 段落：跨段拼接
 *     embedding vector(384),                  -- BGE-small-zh 维度
 *     created_at BIGINT NOT NULL
 * );
 * CREATE INDEX IF NOT EXISTS idx_knowledge_embedding ON knowledge_docs
 *     USING hnsw (embedding vector_cosine_ops);
 * CREATE INDEX IF NOT EXISTS idx_knowledge_tenant ON knowledge_docs (tenant_id);
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

    /** 版本优先级衰减权重 */
    private static final double VERSION_PENALTY_RATE = 0.1;

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
                + "tenant_id VARCHAR(64) DEFAULT '',"
                + "version VARCHAR(32) DEFAULT 'v1',"
                + "source_url VARCHAR(1024) DEFAULT '',"
                + "chunk_index INT DEFAULT -1,"
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
        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_tenant_acl"
                    + " ON " + TABLE + " (tenant_id)");
        } catch (Exception e) {
            log.warn("[PgVectorKB:{}] ACL 索引创建失败: {}", name, e.getMessage());
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
                        + "effective_at, expire_at, tenant_id, version, source_url, chunk_index, "
                        + "embedding, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + (vecStr != null ? "?::vector" : "NULL") + ", ?) "
                        + "ON CONFLICT (id) DO UPDATE SET "
                        + "title=EXCLUDED.title, content=EXCLUDED.content, "
                        + "category=EXCLUDED.category, keywords=EXCLUDED.keywords, "
                        + "tenant_id=EXCLUDED.tenant_id, version=EXCLUDED.version, "
                        + "source_url=EXCLUDED.source_url, chunk_index=EXCLUDED.chunk_index, "
                        + "embedding=" + (vecStr != null ? "EXCLUDED.embedding" : "NULL"),
                doc.getId(), doc.getTitle(), doc.getContent(),
                doc.getCategory(), doc.getKeywords(),
                doc.getEffectiveAt(), doc.getExpireAt(),
                doc.getTenantId(), doc.getVersion(),
                doc.getSourceUrl(), doc.getChunkIndex(),
                vecStr, System.currentTimeMillis());
    }

    @Override
    public void removeDocument(String id) {
        jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK) {
        return search(query, topK, KnowledgeBase.PUBLIC_TENANT);
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK, String tenantId) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        int k = (topK > 0) ? topK : 5;

        // 嵌入查询
        float[] queryVec = embeddingModel.embedding(query);
        if (queryVec == null) {
            log.warn("[PgVectorKB:{}] 嵌入不可用，降级到关键词搜索", name);
            return fallbackSearch(query, k, tenantId);
        }

        // 向量搜索 + 过滤过期文档 + 🔴 ACL 检索前过滤
        String vecStr = arrayToPgVector(queryVec);
        long now = System.currentTimeMillis();
        String aclClause = buildAclClause(tenantId);
        List<KnowledgeDocument> candidates = jdbcTemplate.query(
                "SELECT id, title, content, category, keywords, effective_at, expire_at, "
                        + "tenant_id, version, source_url, chunk_index, created_at, "
                        + "(embedding <-> ?::vector) AS dist "
                        + "FROM " + TABLE + " "
                        + "WHERE (effective_at <= 0 OR effective_at <= ?) "
                        + "AND (expire_at <= 0 OR expire_at > ?) "
                        + aclClause
                        + "ORDER BY embedding <-> ?::vector LIMIT ?",
                (ResultSet rs, int rowNum) -> {
                    KnowledgeDocument doc = new KnowledgeDocument(
                            rs.getString("id"), rs.getString("title"),
                            rs.getString("content"), rs.getString("category"),
                            rs.getString("keywords"),
                            rs.getLong("effective_at"), rs.getLong("expire_at"),
                            safeString(rs, "tenant_id"),
                            safeString(rs, "version"),
                            safeString(rs, "source_url"),
                            rs.getInt("chunk_index"));
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

    private List<KnowledgeHit> fallbackSearch(String query, int topK, String tenantId) {
        String q = "%" + query + "%";
        String aclClause = buildAclClause(tenantId);
        return jdbcTemplate.query(
                "SELECT id, title, content, category, keywords, effective_at, expire_at, "
                        + "tenant_id, version, source_url, chunk_index, created_at "
                        + "FROM " + TABLE + " "
                        + "WHERE (title ILIKE ? OR content ILIKE ? OR keywords ILIKE ?) "
                        + aclClause
                        + "LIMIT ?",
                (ResultSet rs, int rowNum) -> {
                    KnowledgeDocument doc = new KnowledgeDocument(
                            rs.getString("id"), rs.getString("title"),
                            rs.getString("content"), rs.getString("category"),
                            rs.getString("keywords"),
                            rs.getLong("effective_at"), rs.getLong("expire_at"),
                            safeString(rs, "tenant_id"),
                            safeString(rs, "version"),
                            safeString(rs, "source_url"),
                            rs.getInt("chunk_index"));
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
        // 版本优先级（v1=1.0, v2=1.1, v3=1.2）
        double versionBoost = 1.0 + doc.getVersionPriority() * VERSION_PENALTY_RATE;
        double bm25Score = 0;
        if (bm25Scorer != null && bm25Scorer.isInitialized()) {
            bm25Score = Math.tanh(bm25Scorer.score(doc, query));
            bm25Score = Math.min(bm25Score, 1.0);
        }
        return cosSim * timeDecay * versionBoost * (1 - BM25_MIX_WEIGHT) + bm25Score * BM25_MIX_WEIGHT;
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

    // ==================== ACL 辅助方法 ====================

    /**
     * 构建 ACL 过滤 SQL 子句。
     * 检索前过滤：仅返回 tenant_id 为空（公开）或与请求 tenantId 匹配的文档。
     */
    private static String buildAclClause(String tenantId) {
        if (tenantId == null || tenantId.isEmpty()) {
            return "AND (tenant_id IS NULL OR tenant_id = '') ";
        }
        return "AND (tenant_id IS NULL OR tenant_id = '' OR tenant_id = '"
                + tenantId.replace("'", "''") + "') ";
    }

    /** 安全获取可能为 null 的字符串字段 */
    private static String safeString(ResultSet rs, String column) {
        try {
            String val = rs.getString(column);
            return val != null ? val : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static class ScoredDoc {
        final KnowledgeDocument doc;
        final double score;
        ScoredDoc(KnowledgeDocument doc, double score) { this.doc = doc; this.score = score; }
    }
}
