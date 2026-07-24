/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.rag.retrieval.CrossDocumentConflictResolver;
import com.example.smartassistant.common.rag.store.KnowledgeIndexMetaService;
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
 * <h3>生产化改造关键修复（2026-07-14）</h3>
 * <ul>
 *   <li>① 向量维度<b>动态化</b>：从 {@link BgeEmbeddingModel#dimensions()} 取维度建表，
 *       彻底修复硬编码 384 与运行时 bge-large-zh-v1.5（1024 维）冲突导致建表崩溃；</li>
 *   <li>② 检索<b>真实余弦距离</b>：用 {@code (1 - pgvector 余弦距离)} 作为相似度精排，
 *       修复 {@code cosSim} 写死 1.0 导致精排失真；</li>
 *   <li>③ <b>增量 upsert</b> + {@code index_version} 过滤：单文档更新无需全量重建；
 *       检索按 active 索引版本过滤，旧版本不可见但保留可回滚；</li>
 *   <li>④ 去除强制全量 reindex：embedding 在 {@link #addDocument} 内完成，
 *       摄入流程不再全量重算（见 {@code KnowledgeIngestionService}）。</li>
 * </ul>
 */
public class PgVectorKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(PgVectorKnowledgeBase.class);

    /** 表名 */
    private static final String TABLE = "knowledge_docs";

    /** 向量维度：动态取自 BGE 模型（bge-large-zh-v1.5 = 1024），默认 1024 兜底 */
    private final int dimensions;

    private final String name;
    private final BgeEmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;

    /** ⭐ 索引版本元数据服务（检索按 active 版本过滤；可空） */
    private final KnowledgeIndexMetaService indexMetaService;

    /** BM25 评分器（可选） */
    private Bm25Scorer bm25Scorer;

    /** ⭐ 检索侧跨文档冲突消解器（Q6 第二层，可选，null 时不消解） */
    private CrossDocumentConflictResolver conflictResolver;

    /** 余弦相似度阈值 */
    private static final double MIN_SIMILARITY = 0.30;

    /** 时间衰减 λ */
    private static final double TIME_DECAY_LAMBDA = 0.01;

    /** BM25 混合权重 */
    private static final double BM25_MIX_WEIGHT = 0.3;

    /** 版本优先级衰减权重 */
    private static final double VERSION_PENALTY_RATE = 0.1;

    public PgVectorKnowledgeBase(String name, BgeEmbeddingModel embeddingModel,
                                  JdbcTemplate jdbcTemplate, ChineseTokenizer tokenizer) {
        this(name, embeddingModel, jdbcTemplate, tokenizer, null);
    }

    public PgVectorKnowledgeBase(String name, BgeEmbeddingModel embeddingModel,
                                  JdbcTemplate jdbcTemplate, ChineseTokenizer tokenizer,
                                  KnowledgeIndexMetaService indexMetaService) {
        this.name = name;
        this.embeddingModel = embeddingModel;
        int d = embeddingModel != null ? embeddingModel.dimensions() : 0;
        this.dimensions = d > 0 ? d : 1024;
        this.jdbcTemplate = jdbcTemplate;
        this.indexMetaService = indexMetaService;
        if (tokenizer != null) {
            this.bm25Scorer = new Bm25Scorer(tokenizer);
        }
        log.info("[PgVectorKB:{}] 初始化（向量维度={}, indexMeta={}）", name, dimensions,
                indexMetaService != null ? "enabled" : "disabled");
        initSchema();
    }

    /** 自动建表（维度动态化） */
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
                + "security_level INT DEFAULT 0,"
                + "authorized_roles TEXT[] DEFAULT '{}',"
                + "authorized_users TEXT[] DEFAULT '{}',"
                + "parent_doc_id VARCHAR(128) DEFAULT '',"
                + "source_type VARCHAR(32) DEFAULT '',"
                + "raw_checksum VARCHAR(128) DEFAULT '',"
                + "ingest_batch_id VARCHAR(64) DEFAULT '',"
                + "index_version VARCHAR(32) DEFAULT 'v1',"
                + "embedding vector(" + dimensions + "),"
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
        // ⭐ 生产化摄入列迁移（幂等）
        try {
            jdbcTemplate.execute("ALTER TABLE " + TABLE
                    + " ADD COLUMN IF NOT EXISTS parent_doc_id VARCHAR(128) DEFAULT ''");
            jdbcTemplate.execute("ALTER TABLE " + TABLE
                    + " ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) DEFAULT ''");
            jdbcTemplate.execute("ALTER TABLE " + TABLE
                    + " ADD COLUMN IF NOT EXISTS raw_checksum VARCHAR(128) DEFAULT ''");
            jdbcTemplate.execute("ALTER TABLE " + TABLE
                    + " ADD COLUMN IF NOT EXISTS ingest_batch_id VARCHAR(64) DEFAULT ''");
            jdbcTemplate.execute("ALTER TABLE " + TABLE
                    + " ADD COLUMN IF NOT EXISTS index_version VARCHAR(32) DEFAULT 'v1'");
            jdbcTemplate.execute("ALTER TABLE " + TABLE
                    + " ADD COLUMN IF NOT EXISTS chunk_role VARCHAR(16) DEFAULT 'STANDALONE'");
        } catch (Exception e) {
            log.warn("[PgVectorKB:{}] 摄入列迁移失败（可忽略，可能已存在）: {}", name, e.getMessage());
        }
    }

    @Override
    public String getName() { return name; }

    @Override
    public void addDocument(KnowledgeDocument doc) {
        if (doc == null) return;
        // ⭐ Parent-Child：父块（PARENT）不嵌入——父块是整接口/大段上下文，超嵌入窗且仅供 LLM 阅读，
        // 跳过嵌入既省算力，又避免 NULL embedding 参与检索导致 dist=NaN、余弦精排崩坏。
        float[] vec = (doc.getChunkRole() == ChunkRole.PARENT)
                ? null : embeddingModel.embedding(doc.toEmbedText());
        String vecStr = vec != null ? arrayToPgVector(vec) : null;

        jdbcTemplate.update(
                "INSERT INTO " + TABLE + " (id, title, content, category, keywords, "
                        + "effective_at, expire_at, tenant_id, version, source_url, chunk_index, "
                        + "authority_level, document_status, security_level, "
                        + "authorized_roles, authorized_users, "
                        + "parent_doc_id, source_type, raw_checksum, ingest_batch_id, index_version, "
                        + "chunk_role, embedding, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + toPgTextArray(doc.getAuthorizedRoles()) + ", "
                        + toPgTextArray(doc.getAuthorizedUsers()) + ", "
                        + "?, ?, ?, ?, ?, ?, "
                        + (vecStr != null ? "?::vector" : "NULL") + ", ?) "
                        + "ON CONFLICT (id) DO UPDATE SET "
                        + "title=EXCLUDED.title, content=EXCLUDED.content, "
                        + "category=EXCLUDED.category, keywords=EXCLUDED.keywords, "
                        + "tenant_id=EXCLUDED.tenant_id, version=EXCLUDED.version, "
                        + "source_url=EXCLUDED.source_url, chunk_index=EXCLUDED.chunk_index, "
                        + "authority_level=EXCLUDED.authority_level, document_status=EXCLUDED.document_status, "
                        + "security_level=EXCLUDED.security_level, "
                        + "authorized_roles=EXCLUDED.authorized_roles, authorized_users=EXCLUDED.authorized_users, "
                        + "parent_doc_id=EXCLUDED.parent_doc_id, source_type=EXCLUDED.source_type, "
                        + "raw_checksum=EXCLUDED.raw_checksum, ingest_batch_id=EXCLUDED.ingest_batch_id, "
                        + "index_version=EXCLUDED.index_version, "
                        + "chunk_role=EXCLUDED.chunk_role, "
                        + "embedding=" + (vecStr != null ? "EXCLUDED.embedding" : "NULL"),
                doc.getId(), doc.getTitle(), doc.getContent(),
                doc.getCategory(), doc.getKeywords(),
                doc.getEffectiveAt(), doc.getExpireAt(),
                doc.getTenantId(), doc.getVersion(),
                doc.getSourceUrl(), doc.getChunkIndex(),
                doc.getAuthorityLevel().getRank(), doc.getDocumentStatus().name(),
                doc.getSecurityLevel(),
                doc.getParentDocId(), doc.getSourceType(), doc.getRawChecksum(),
                doc.getIngestBatchId(), doc.getIndexVersion(),
                doc.getChunkRole().name(),
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
        return search(query, topK, AclContext.forTenant(tenantId));
    }

    /**
     * ⭐ 细粒度 ACL 检索（文章⑤：权限进入检索层，服务端生成 filter）。
     * <p>在租户隔离之上，按角色 / 用户 / 安全等级做检索前过滤；并按 active {@code index_version}
     * 过滤（保证查到的向量是同一索引版本构建的）。相似度使用 pgvector 真实余弦距离
     * （{@code cosSim = 1 - distance}）精排，修复旧版写死 1.0 的精排失真。</p>
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

        // ⭐ 索引版本过滤子句（active 版本，缺失则不限制以兼容历史数据）
        String activeVersion = (indexMetaService != null) ? indexMetaService.getActiveVersion() : null;
        boolean versionFilter = activeVersion != null && !activeVersion.isBlank();
        String versionClause = versionFilter
                ? "AND (index_version IS NULL OR index_version = ?) " : "";

        // 向量搜索 + 过滤过期文档 + 🔴 ACL 检索前过滤 + 🔴 状态过滤 + ⭐ index_version 过滤
        String vecStr = arrayToPgVector(queryVec);
        long now = System.currentTimeMillis();
        String aclClause = buildAclClause(acl);

        List<Object> params = new ArrayList<>();
        params.add(vecStr);   // embedding <-> ?
        params.add(now);      // effective_at
        params.add(now);      // expire_at
        if (versionFilter) {
            params.add(activeVersion);
        }
        params.add(vecStr);   // ORDER BY embedding <-> ?
        params.add(50);       // LIMIT

        String sql = "SELECT id, title, content, category, keywords, effective_at, expire_at, "
                + "tenant_id, version, source_url, chunk_index, created_at, "
                + "authority_level, document_status, security_level, "
                + "authorized_roles, authorized_users, parent_doc_id, source_type, "
                + "raw_checksum, ingest_batch_id, index_version, chunk_role, "
                + "(embedding <-> ?::vector) AS dist "
                + "FROM " + TABLE + " "
                + "WHERE (effective_at <= 0 OR effective_at <= ?) "
                + "AND (expire_at <= 0 OR expire_at > ?) "
                + "AND (document_status IS NULL OR document_status = 'ACTIVE') "
                + "AND embedding IS NOT NULL "
                + aclClause
                + versionClause
                + "ORDER BY embedding <-> ?::vector LIMIT ?";

        // 精排：BM25 + 时间衰减 + ⭐ 真实余弦距离（dist 由 SQL 计算，随文档一同取回）
        List<DocWithDist> candidates = jdbcTemplate.query(sql,
                (ResultSet rs, int rowNum) -> {
                    KnowledgeDocument doc = mapDoc(rs);
                    double dist = rs.getDouble("dist");
                    return new DocWithDist(doc, dist);
                },
                params.toArray());

        List<ScoredDoc> scored = candidates.stream()
                .filter(d -> d.doc.isRetrievable())
                .map(d -> {
                    double cosSim = realCosineScore(d.dist);
                    double score = composeScore(cosSim, d.doc, query);
                    return new ScoredDoc(d.doc, score);
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
     * ⭐ Parent-Child 检索回链（JavaGuide 方案）：
     * 向量检索仅命中子块（CHILD），命中后通过其 {@code parentDocId} 二次查询取父块（PARENT），
     * 返回父块整内容供 LLM 阅读，同时保留子块的检索得分与排序，使最相关接口的完整上下文优先呈现。
     * <p>父块入库时 embedding=NULL（见 {@link #addDocument}），故普通 {@link #search} 已通过
     * {@code AND embedding IS NOT NULL} 将其排除，本方法不会把父块当作检索候选；父块仅作为回链结果返回。</p>
     * <p>若命中文档自身无 parent（STANDALONE 或已是 PARENT），则原样返回该文档。</p>
     */
    @Override
    public List<KnowledgeHit> searchWithParentExpansion(String query, int topK, AclContext acl) {
        // 1) 仅检子块（search 已排除 embedding=NULL 的父块）
        List<KnowledgeHit> childHits = search(query, topK, acl);
        if (childHits.isEmpty()) return childHits;

        // 2) 按子块命中顺序收集父块，去重保留首遇（=最相关接口优先）
        Map<String, KnowledgeHit> expanded = new LinkedHashMap<>();
        for (KnowledgeHit hit : childHits) {
            KnowledgeDocument doc = hit.getDocument();
            String parentId = doc.getParentDocId();
            if (parentId == null || parentId.isBlank()) {
                // 无 parent：独立块或父块本身，原样保留
                expanded.put(doc.getId(), hit);
                continue;
            }
            if (expanded.containsKey(parentId)) continue; // 同父块只取一次
            KnowledgeDocument parent = loadById(parentId, acl);
            if (parent != null) {
                // 父块整内容 + 子块得分（保留子块相关度排序）
                expanded.put(parentId, new KnowledgeHit(parent, hit.getScore()));
            } else {
                // 父块未找到（被 ACL 过滤/已删除），降级保留子块
                expanded.put(doc.getId(), hit);
            }
        }
        return new ArrayList<>(expanded.values());
    }

    /** 按 ID + ACL 二次查询单个文档（父块回链用）；无权限或无记录返回 null */
    private KnowledgeDocument loadById(String id, AclContext acl) {
        String aclClause = buildAclClause(acl);
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, title, content, category, keywords, effective_at, expire_at, "
                            + "tenant_id, version, source_url, chunk_index, created_at, "
                            + "authority_level, document_status, security_level, "
                            + "authorized_roles, authorized_users, parent_doc_id, source_type, "
                            + "raw_checksum, ingest_batch_id, index_version, chunk_role "
                            + "FROM " + TABLE + " "
                            + "WHERE id = ? "
                            + "AND (document_status IS NULL OR document_status = 'ACTIVE') "
                            + aclClause,
                    (ResultSet rs, int rowNum) -> mapDoc(rs), id);
        } catch (Exception e) {
            log.warn("[PgVectorKB:{}] 父块回链加载失败: id={}, {}", name, id, e.getMessage());
            return null;
        }
    }

    /**
     * 设置检索侧跨文档冲突消解器（Q6 第二层）。
     * <p>在精排之后、返回之前生效；null 时跳过消解。</p>
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
        // 重新计算所有 embedding（接口要求保留；摄入流程不再调用，避免全量重算）
        List<KnowledgeDocument> allDocs = listAll();
        for (KnowledgeDocument doc : allDocs) {
            // ⭐ 父块（PARENT）不嵌入：保持 embedding=NULL，避免污染向量索引与回链语义
            if (doc.getChunkRole() == ChunkRole.PARENT) continue;
            float[] vec = embeddingModel.embedding(doc.toEmbedText());
            if (vec != null) {
                jdbcTemplate.update(
                        "UPDATE " + TABLE + " SET embedding = ?::vector WHERE id = ?",
                        arrayToPgVector(vec), doc.getId());
            }
        }
        if (bm25Scorer != null) {
            bm25Scorer.initialize(allDocs);
        }
        log.info("[PgVectorKB:{}] 重新索引完成: {} 篇文档", name, allDocs.size());
    }

    // ==================== 内部方法 ====================

    /**
     * ⭐ 列出全部文档（REQ-4 内存快照刷新用）。
     * <p>读取全部列并通过 {@link #mapDoc} 还原为 {@link KnowledgeDocument}，保证快照字段完整。</p>
     */
    @Override
    public List<KnowledgeDocument> listAll() {
        String sql = "SELECT id, title, content, category, keywords, effective_at, expire_at, "
                + "tenant_id, version, source_url, chunk_index, created_at, "
                + "authority_level, document_status, security_level, "
                + "authorized_roles, authorized_users, parent_doc_id, source_type, "
                + "raw_checksum, ingest_batch_id, index_version, chunk_role FROM " + TABLE;
        try {
            return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> mapDoc(rs));
        } catch (Exception e) {
            log.warn("[PgVectorKB:{}] listAll 失败: {}", name, e.getMessage());
            return List.of();
        }
    }

    private List<KnowledgeHit> fallbackSearch(String query, int topK, AclContext acl) {
        String q = "%" + query + "%";
        String aclClause = buildAclClause(acl);
        return jdbcTemplate.query(
                "SELECT id, title, content, category, keywords, effective_at, expire_at, "
                        + "tenant_id, version, source_url, chunk_index, created_at, "
                        + "authority_level, document_status, security_level, "
                        + "authorized_roles, authorized_users, parent_doc_id, source_type, "
                + "raw_checksum, ingest_batch_id, index_version, chunk_role "
                + "FROM " + TABLE + " "
                + "WHERE (title ILIKE ? OR content ILIKE ? OR keywords ILIKE ?) "
                        + "AND (document_status IS NULL OR document_status = 'ACTIVE') "
                        + aclClause
                        + "LIMIT ?",
                (ResultSet rs, int rowNum) -> new KnowledgeHit(mapDoc(rs), 0.5),
                q, q, q, topK);
    }

    /**
     * pgvector 余弦距离 → 余弦相似度。
     * <p>pgvector 的 {@code <->} 算子返回余弦距离（范围 [0,2]，0 表示完全一致），
     * 余弦相似度 = {@code 1 - distance}。</p>
     */
    static double realCosineScore(double dist) {
        return 1.0 - dist;
    }

    private double composeScore(double cosSim, KnowledgeDocument doc, String query) {
        double timeDecay = 1.0;
        if (doc.getExpireAt() > 0) {
            long daysToExpire = (doc.getExpireAt() - System.currentTimeMillis()) / 86400000;
            if (daysToExpire > 0) {
                timeDecay = Math.exp(-TIME_DECAY_LAMBDA * (365 - daysToExpire));
            }
        }
        double versionBoost = 1.0 + doc.getVersionPriority() * VERSION_PENALTY_RATE;
        double authorityBoost = 1.0 + (doc.getAuthorityLevel().getRank() / 4.0 - 0.5) * 0.2;
        double bm25Score = 0;
        if (bm25Scorer != null && bm25Scorer.isInitialized()) {
            bm25Score = Math.tanh(bm25Scorer.score(doc, query));
            bm25Score = Math.min(bm25Score, 1.0);
        }
        return cosSim * timeDecay * versionBoost * authorityBoost * (1 - BM25_MIX_WEIGHT) + bm25Score * BM25_MIX_WEIGHT;
    }

    /** float[] → pgvector 字符串格式 '[0.1,0.2,...]' */
    private String arrayToPgVector(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", vec[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    // ==================== ACL 辅助方法 ====================

    static String buildAclClause(AclContext acl) {
        StringBuilder sb = new StringBuilder();

        String tenantId = acl.getTenantId();
        if (tenantId == null || tenantId.isEmpty()) {
            sb.append("AND (tenant_id IS NULL OR tenant_id = '') ");
        } else {
            sb.append("AND (tenant_id IS NULL OR tenant_id = '' OR tenant_id = '")
              .append(tenantId.replace("'", "''")).append("') ");
        }

        sb.append("AND (security_level IS NULL OR security_level <= ")
          .append(acl.getSecurityClearance()).append(") ");

        String rolesLiteral = acl.getRoles().stream()
                .map(r -> "'" + r.replace("'", "''") + "'")
                .collect(Collectors.joining(","));
        sb.append("AND (authorized_roles IS NULL OR array_length(authorized_roles,1) IS NULL ")
          .append("OR authorized_roles && ARRAY[").append(rolesLiteral).append("]::text[]) ");

        String userId = acl.getUserId();
        sb.append("AND (authorized_users IS NULL OR array_length(authorized_users,1) IS NULL ")
          .append("OR '").append(userId != null ? userId.replace("'", "''") : "")
          .append("' = ANY(authorized_users)) ");

        return sb.toString();
    }

    /** 从 ResultSet 映射为 KnowledgeDocument（含细粒度 ACL + 生产化摄入字段） */
    private KnowledgeDocument mapDoc(ResultSet rs) throws java.sql.SQLException {
        return new KnowledgeDocument(
                rs.getString("id"), rs.getString("title"),
                rs.getString("content"), rs.getString("category"),
                rs.getString("keywords"),
                rs.getLong("effective_at"), rs.getLong("expire_at"),
                safeString(rs, "tenant_id"),
                safeString(rs, "version"),
                safeString(rs, "source_url"),
                rs.getInt("chunk_index"),
                safeString(rs, "parent_doc_id"),
                AuthorityLevel.fromRank(rs.getInt("authority_level")),
                DocumentStatus.fromCode(safeString(rs, "document_status")),
                safeString(rs, "index_version"),
                readStringSet(rs, "authorized_roles"),
                readStringSet(rs, "authorized_users"),
                rs.getObject("security_level") != null ? rs.getInt("security_level") : 0,
                parseChunkRole(safeString(rs, "chunk_role")),
                safeString(rs, "source_type"),
                safeString(rs, "raw_checksum"),
                safeString(rs, "ingest_batch_id"));
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

    /** chunk_role 字符串 → ChunkRole 枚举（未知/空 → STANDALONE） */
    private static ChunkRole parseChunkRole(String role) {
        if (role == null || role.isBlank()) return ChunkRole.STANDALONE;
        try {
            return ChunkRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ChunkRole.STANDALONE;
        }
    }

    private static class ScoredDoc {
        final KnowledgeDocument doc;
        final double score;
        ScoredDoc(KnowledgeDocument doc, double score) { this.doc = doc; this.score = score; }
    }

    /** 文档 + 向量余弦距离（检索阶段一并取回，用于真实相似度精排） */
    private static class DocWithDist {
        final KnowledgeDocument doc;
        final double dist;
        DocWithDist(KnowledgeDocument doc, double dist) { this.doc = doc; this.dist = dist; }
    }
}
