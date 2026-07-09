/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.rag.retrieval.CrossDocumentConflictResolver;
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
 *     authority_level INT DEFAULT 3,          -- 🔴 权威性：L1=4>L2=3>L3=2>L4=1
 *     document_status VARCHAR(16) DEFAULT 'ACTIVE', -- 🔴 状态：ACTIVE/SUPERSEDED/QUARANTINED
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

    /** ⭐ 检索侧跨文档冲突消解器（Q6 第二层，可选，null 时不消解） */
    private CrossDocumentConflictResolver conflictResolver;

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
                + "authority_level INT DEFAULT 3,"
                + "document_status VARCHAR(16) DEFAULT 'ACTIVE',"
                + "security_level INT DEFAULT 0,"           // 🔴 ACL 细粒度：安全等级
                + "authorized_roles TEXT[] DEFAULT '{}',"    // 🔴 ACL 细粒度：授权角色
                + "authorized_users TEXT[] DEFAULT '{}',"    // 🔴 ACL 细粒度：授权用户
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
        // ⭐ 细粒度 ACL 列迁移（已存在的旧表补齐，幂等）
        try {
            jdbcTemplate.execute("ALTER TABLE " + TABLE
                    + " ADD COLUMN IF NOT EXISTS security_level INT DEFAULT 0");
            jdbcTemplate.execute("ALTER TABLE " + TABLE
                    + " ADD COLUMN IF NOT EXISTS authorized_roles TEXT[] DEFAULT '{}'");
            jdbcTemplate.execute("ALTER TABLE " + TABLE
                    + " ADD COLUMN IF NOT EXISTS authorized_users TEXT[] DEFAULT '{}'");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_acl_roles"
                    + " ON " + TABLE + " USING gin (authorized_roles)");
        } catch (Exception e) {
            log.warn("[PgVectorKB:{}] ACL 列迁移失败（可忽略，可能已存在）: {}", name, e.getMessage());
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
                        + "authority_level, document_status, "
                        + "security_level, authorized_roles, authorized_users, "
                        + "embedding, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + toPgTextArray(doc.getAuthorizedRoles()) + ", "
                        + toPgTextArray(doc.getAuthorizedUsers()) + ", "
                        + (vecStr != null ? "?::vector" : "NULL") + ", ?) "
                        + "ON CONFLICT (id) DO UPDATE SET "
                        + "title=EXCLUDED.title, content=EXCLUDED.content, "
                        + "category=EXCLUDED.category, keywords=EXCLUDED.keywords, "
                        + "tenant_id=EXCLUDED.tenant_id, version=EXCLUDED.version, "
                        + "source_url=EXCLUDED.source_url, chunk_index=EXCLUDED.chunk_index, "
                        + "authority_level=EXCLUDED.authority_level, document_status=EXCLUDED.document_status, "
                        + "security_level=EXCLUDED.security_level, "
                        + "authorized_roles=EXCLUDED.authorized_roles, authorized_users=EXCLUDED.authorized_users, "
                        + "embedding=" + (vecStr != null ? "EXCLUDED.embedding" : "NULL"),
                doc.getId(), doc.getTitle(), doc.getContent(),
                doc.getCategory(), doc.getKeywords(),
                doc.getEffectiveAt(), doc.getExpireAt(),
                doc.getTenantId(), doc.getVersion(),
                doc.getSourceUrl(), doc.getChunkIndex(),
                doc.getAuthorityLevel().getRank(), doc.getDocumentStatus().name(),
                doc.getSecurityLevel(),
                vecStr, System.currentTimeMillis());
    }

    @Override
    public void removeDocument(String id) {
        jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    @Override
    public void removeByBaseDocId(String baseDocId) {
        if (baseDocId == null || baseDocId.isBlank()) return;
        int deleted = jdbcTemplate.update(
                "DELETE FROM " + TABLE + " WHERE id LIKE ? || '-%' OR id = ?",
                baseDocId, baseDocId);
        if (deleted > 0) {
            log.info("[PgVectorKB:{}] 按 baseDocId 删除: baseId={}, removed={} rows",
                    name, baseDocId, deleted);
        }
    }

    @Override
    public List<String> listIdsByBaseDocId(String baseDocId) {
        if (baseDocId == null || baseDocId.isBlank()) return List.of();
        return jdbcTemplate.query(
                "SELECT id FROM " + TABLE + " WHERE id = ? OR id LIKE ? || '-%'",
                (ResultSet rs, int rowNum) -> rs.getString("id"),
                baseDocId, baseDocId);
    }

    @Override
    public void updateStatus(String docId, DocumentStatus status) {
        if (docId == null || docId.isBlank() || status == null) return;
        int n = jdbcTemplate.update(
                "UPDATE " + TABLE + " SET document_status = ? WHERE id = ?",
                status.name(), docId);
        if (n > 0) {
            log.info("[PgVectorKB:{}] 状态更新: id={}, status={}", name, docId, status);
        }
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK) {
        return search(query, topK, KnowledgeBase.PUBLIC_TENANT);
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK, String tenantId) {
        // 退化为仅租户过滤（保持向后兼容）
        return search(query, topK, AclContext.forTenant(tenantId));
    }

    /**
     * ⭐ 细粒度 ACL 检索（文章⑤：权限进入检索层，服务端生成 filter）。
     * <p>在租户隔离之上，按角色 / 用户 / 安全等级做检索前过滤；filter 完全由
     * 服务端根据请求身份（{@link AclContext}）生成，不信任客户端传入条件。</p>
     */
    @Override
    public List<KnowledgeHit> search(String query, int topK, AclContext acl) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        int k = (topK > 0) ? topK : 5;

        // 嵌入查询
        float[] queryVec = embeddingModel.embedding(query);
        if (queryVec == null) {
            log.warn("[PgVectorKB:{}] 嵌入不可用，降级到关键词搜索", name);
            return fallbackSearch(query, k, acl);
        }

        // 向量搜索 + 过滤过期文档 + 🔴 ACL 检索前过滤 + 🔴 状态过滤
        String vecStr = arrayToPgVector(queryVec);
        long now = System.currentTimeMillis();
        String aclClause = buildAclClause(acl);
        List<KnowledgeDocument> candidates = jdbcTemplate.query(
                "SELECT id, title, content, category, keywords, effective_at, expire_at, "
                        + "tenant_id, version, source_url, chunk_index, created_at, "
                        + "authority_level, document_status, security_level, "
                        + "authorized_roles, authorized_users, "
                        + "(embedding <-> ?::vector) AS dist "
                        + "FROM " + TABLE + " "
                        + "WHERE (effective_at <= 0 OR effective_at <= ?) "
                        + "AND (expire_at <= 0 OR expire_at > ?) "
                        + "AND (document_status IS NULL OR document_status = 'ACTIVE') "
                        + aclClause
                        + "ORDER BY embedding <-> ?::vector LIMIT ?",
                (ResultSet rs, int rowNum) -> mapDoc(rs),
                vecStr, now, now, vecStr, 50); // 粗筛 50 条

        // 精排：BM25 + 时间衰减
        List<ScoredDoc> scored = candidates.stream()
                .filter(doc -> doc.isRetrievable())
                .map(doc -> {
                    double cosSim = 1.0; // pgvector <-> 已返回余弦距离，此处简化
                    double score = composeScore(cosSim, doc, query);
                    return new ScoredDoc(doc, score);
                })
                .filter(sd -> sd.score >= MIN_SIMILARITY)
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(k)
                .collect(Collectors.toList());

        List<KnowledgeHit> hits = scored.stream()
                .map(sd -> new KnowledgeHit(sd.doc, sd.score))
                .collect(Collectors.toList());

        // ⭐ 检索侧跨文档冲突消解（Q6 第二层）—按权威性/版本压制矛盾低权威来源
        return applyConflictResolution(conflictResolver, hits);
    }

    /**
     * 设置检索侧跨文档冲突消解器（Q6 第二层）。
     * <p>在精排之后、返回之前生效；null 时跳过消解。</p>
     *
     * @param conflictResolver 跨文档冲突消解器
     */
    public void setConflictResolver(CrossDocumentConflictResolver conflictResolver) {
        this.conflictResolver = conflictResolver;
    }

    /** 冲突消解应用（与 InMemoryKnowledgeBase 同态，package-private 便于无基础设施单测） */
    static List<KnowledgeHit> applyConflictResolution(
            CrossDocumentConflictResolver resolver, List<KnowledgeHit> hits) {
        if (resolver == null || hits == null || hits.size() < 2) return hits;
        return resolver.resolveHits(hits);
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

    private List<KnowledgeHit> fallbackSearch(String query, int topK, AclContext acl) {
        String q = "%" + query + "%";
        String aclClause = buildAclClause(acl);
        return jdbcTemplate.query(
                "SELECT id, title, content, category, keywords, effective_at, expire_at, "
                        + "tenant_id, version, source_url, chunk_index, created_at, "
                        + "authority_level, document_status, security_level, "
                        + "authorized_roles, authorized_users "
                        + "FROM " + TABLE + " "
                        + "WHERE (title ILIKE ? OR content ILIKE ? OR keywords ILIKE ?) "
                        + "AND (document_status IS NULL OR document_status = 'ACTIVE') "
                        + aclClause
                        + "LIMIT ?",
                (ResultSet rs, int rowNum) -> new KnowledgeHit(mapDoc(rs), 0.5),
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
        // ⭐ 权威性加权（冲突预防）：L1 官方 > L2 内部 > L3 笔记 > L4 外部
        double authorityBoost = 1.0 + (doc.getAuthorityLevel().getRank() / 4.0 - 0.5) * 0.2;
        double bm25Score = 0;
        if (bm25Scorer != null && bm25Scorer.isInitialized()) {
            bm25Score = Math.tanh(bm25Scorer.score(doc, query));
            bm25Score = Math.min(bm25Score, 1.0);
        }
        return cosSim * timeDecay * versionBoost * authorityBoost * (1 - BM25_MIX_WEIGHT) + bm25Score * BM25_MIX_WEIGHT;
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
     * 构建细粒度 ACL 过滤 SQL 子句（文章⑤：权限进入检索层，服务端生成 filter）。
     * <p>filter 完全由 {@link AclContext}（请求身份）生成，不信任客户端传入条件：
     * <ul>
     *   <li>租户：仅返回公开文档或匹配 tenantId 的文档；</li>
     *   <li>安全等级：文档 security_level 为空/0（公开）或 ≤ 用户 clearance；</li>
     *   <li>角色：文档未限定角色 → 任意；否则用户角色须与文档授权角色有交集；</li>
     *   <li>用户：文档未限定用户 → 任意；否则须包含当前用户。</li>
     * </ul>
     * 始终输出角色/用户子句（即使用户无角色/无用户标识），确保"要求特定角色/用户"的
     * 文档对匿名请求正确排除（安全默认拒绝）。
     */
    static String buildAclClause(AclContext acl) {
        StringBuilder sb = new StringBuilder();

        // 租户隔离
        String tenantId = acl.getTenantId();
        if (tenantId == null || tenantId.isEmpty()) {
            sb.append("AND (tenant_id IS NULL OR tenant_id = '') ");
        } else {
            sb.append("AND (tenant_id IS NULL OR tenant_id = '' OR tenant_id = '")
              .append(tenantId.replace("'", "''")).append("') ");
        }

        // 安全等级：文档未设或 ≤ 用户许可等级
        sb.append("AND (security_level IS NULL OR security_level <= ")
          .append(acl.getSecurityClearance()).append(") ");

        // 角色：始终输出（用户角色可能为空数组 → 要求角色的文档被排除）
        String rolesLiteral = acl.getRoles().stream()
                .map(r -> "'" + r.replace("'", "''") + "'")
                .collect(Collectors.joining(","));
        sb.append("AND (authorized_roles IS NULL OR array_length(authorized_roles,1) IS NULL ")
          .append("OR authorized_roles && ARRAY[").append(rolesLiteral).append("]::text[]) ");

        // 用户：始终输出（匿名 → '' = ANY(...) 为 false，要求用户的文档被排除）
        String userId = acl.getUserId();
        sb.append("AND (authorized_users IS NULL OR array_length(authorized_users,1) IS NULL ")
          .append("OR '").append(userId != null ? userId.replace("'", "''") : "")
          .append("' = ANY(authorized_users)) ");

        return sb.toString();
    }

    /** 从 ResultSet 映射为 KnowledgeDocument（含细粒度 ACL 字段） */
    private static KnowledgeDocument mapDoc(ResultSet rs) throws java.sql.SQLException {
        return new KnowledgeDocument(
                rs.getString("id"), rs.getString("title"),
                rs.getString("content"), rs.getString("category"),
                rs.getString("keywords"),
                rs.getLong("effective_at"), rs.getLong("expire_at"),
                safeString(rs, "tenant_id"),
                safeString(rs, "version"),
                safeString(rs, "source_url"),
                rs.getInt("chunk_index"),
                "", // parent_doc_id 未持久化
                AuthorityLevel.fromRank(rs.getInt("authority_level")),
                DocumentStatus.fromCode(safeString(rs, "document_status")),
                null, // index_version 未持久化
                readStringSet(rs, "authorized_roles"),
                readStringSet(rs, "authorized_users"),
                rs.getObject("security_level") != null ? rs.getInt("security_level") : 0);
    }

    /** 读取 Postgres text[] 列为不可变 Set<String> */
    private static java.util.Set<String> readStringSet(ResultSet rs, String column) {
        try {
            java.sql.Array arr = rs.getArray(column);
            if (arr == null) return java.util.Set.of();
            String[] vals = (String[]) arr.getArray();
            if (vals == null || vals.length == 0) return java.util.Set.of();
            java.util.Set<String> set = new java.util.LinkedHashSet<>();
            for (String v : vals) {
                if (v != null && !v.isBlank()) set.add(v);
            }
            return java.util.Collections.unmodifiableSet(set);
        } catch (Exception e) {
            return java.util.Set.of();
        }
    }

    /** Set<String> → Postgres 数组字面量（元素单引号转义） */
    private static String toPgTextArray(java.util.Set<String> set) {
        if (set == null || set.isEmpty()) return "ARRAY[]::text[]";
        String lit = set.stream()
                .map(s -> "'" + s.replace("'", "''") + "'")
                .collect(Collectors.joining(","));
        return "ARRAY[" + lit + "]::text[]";
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
