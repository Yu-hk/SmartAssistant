/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.*;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.InsertParam.Field;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import io.milvus.response.QueryResultsWrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 基于 Milvus 向量数据库的知识库持久化实现。
 * <p>
 * 文档和向量存储在 Milvus 中，支持持久化、跨实例共享、HNSW 索引加速。
 * 作为 {@link PgVectorKnowledgeBase} 的替代方案，适用于大规模向量检索场景。
 * </p>
 *
 * <p>Milvus Collection 设计：</p>
 * <pre>
 * Collection: {name}_kb
 * ├── id            (Int64, 主键, auto_id=false)
 * ├── doc_id        (VarChar, 文档原始 ID, maxLength=128)
 * ├── title         (VarChar, maxLength=1024)
 * ├── content       (VarChar, maxLength=65535)
 * ├── category      (VarChar, maxLength=64)
 * ├── keywords      (VarChar, maxLength=512)
 * ├── effective_at  (Int64)
 * ├── expire_at     (Int64)
 * ├── tenant_id     (VarChar, 64)     ← 🔴 ACL：租户隔离
 * ├── version       (VarChar, 32)     ← 🔴 版本：灰度回滚
 * ├── source_url    (VarChar, 1024)   ← 🟡 来源：引用回链
 * ├── chunk_index   (Int64)           ← 🟡 段落：跨段拼接
 * ├── updated_at    (Int64)           ← 🟡 时效：更新追踪
 * ├── embedding     (FloatVector, dim=384)
 * └── created_at    (Int64)
 *
 * Index: IVF_FLAT (nlist=128), metric=COSINE
 * </pre>
 */
public class MilvusKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(MilvusKnowledgeBase.class);

    /** BGE-small-zh 向量维度 */
    private static final int DIMENSIONS = 384;

    /** 连接超时（毫秒） */
    private static final long CONNECT_TIMEOUT_MS = 5000;

    /** 索引参数 */
    private static final String INDEX_PARAM = "{\"nlist\": 128}";

    /** 搜索参数 */
    private static final String SEARCH_PARAM = "{\"nprobe\": 16}";

    private final String name;
    private final String collectionName;
    private final BgeEmbeddingModel embeddingModel;
    private final MilvusServiceClient client;
    private final Bm25Scorer bm25Scorer;

    /** BM25 混合权重 */
    private static final double BM25_MIX_WEIGHT = 0.3;

    /** 余弦相似度阈值 */
    private static final double MIN_SIMILARITY = 0.30;

    public MilvusKnowledgeBase(String name, BgeEmbeddingModel embeddingModel,
                                String host, int port, ChineseTokenizer tokenizer) {
        this.name = name;
        this.collectionName = name + "_kb";
        this.embeddingModel = embeddingModel;

        // 连接 Milvus
        this.client = new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(host)
                        .withPort(port)
                        .withConnectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build());

        // BM25 评分器（可选）
        this.bm25Scorer = tokenizer != null ? new Bm25Scorer(tokenizer) : null;

        initCollection();
    }

    /** 自动创建 Collection + 索引 */
    private void initCollection() {
        try {
            R<Boolean> hasResp = client.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            if (hasResp.getStatus() != 0) {
                log.warn("[MilvusKB:{}] 连接检查失败: {}", name, hasResp.getMessage());
                return;
            }
            if (hasResp.getData()) {
                log.info("[MilvusKB:{}] Collection 已存在: {}", name, collectionName);
                client.loadCollection(LoadCollectionParam.newBuilder()
                        .withCollectionName(collectionName).build());
                return;
            }

            // 创建 Collection
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withDescription("SmartAssistant Knowledge Base: " + name)
                    .addFieldType(FieldType.newBuilder()
                            .withName("id").withDataType(DataType.Int64)
                            .withPrimaryKey(true).withAutoID(false).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("doc_id").withDataType(DataType.VarChar).withMaxLength(128).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("title").withDataType(DataType.VarChar).withMaxLength(1024).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("content").withDataType(DataType.VarChar).withMaxLength(65535).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("category").withDataType(DataType.VarChar).withMaxLength(64).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("keywords").withDataType(DataType.VarChar).withMaxLength(512).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("effective_at").withDataType(DataType.Int64).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("expire_at").withDataType(DataType.Int64).build())
                    // ⭐ 生产级 6 类字段（ACL/版本/来源/索引/时效）
                    .addFieldType(FieldType.newBuilder()
                            .withName("tenant_id").withDataType(DataType.VarChar).withMaxLength(64).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("version").withDataType(DataType.VarChar).withMaxLength(32).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("source_url").withDataType(DataType.VarChar).withMaxLength(1024).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("chunk_index").withDataType(DataType.Int64).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("updated_at").withDataType(DataType.Int64).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("embedding").withDataType(DataType.FloatVector).withDimension(DIMENSIONS).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("created_at").withDataType(DataType.Int64).build())
                    .build();

            R<RpcStatus> createResp = client.createCollection(createParam);
            if (createResp.getStatus() != 0) {
                log.warn("[MilvusKB:{}] 创建 Collection 失败: {}", name, createResp.getMessage());
                return;
            }
            log.info("[MilvusKB:{}] Collection 已创建: {}", name, collectionName);

            // 创建向量索引
            client.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam(INDEX_PARAM)
                    .build());

            // 加载到内存
            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collectionName).build());

            log.info("[MilvusKB:{}] Collection 索引创建完成", name);
        } catch (Exception e) {
            log.warn("[MilvusKB:{}] 初始化失败: {}", name, e.getMessage());
        }
    }

    @Override
    public String getName() { return name; }

    @Override
    public void addDocument(KnowledgeDocument doc) {
        if (doc == null) return;
        try {
            float[] vec = embeddingModel.embedding(doc.toEmbedText());
            if (vec == null) {
                log.warn("[MilvusKB:{}] 嵌入不可用，跳过文档: {}", name, doc.getId());
                return;
            }

            // 使用 SHA-256 截断为 64 位作为 Milvus Int64 ID（避免 hashCode 冲突）
            long milvusId = hashToLong(doc.getId());

            List<Field> fields = new ArrayList<>();

            // Int64 主键
            fields.add(new Field("id", List.of(milvusId)));
            fields.add(new Field("doc_id", List.of(doc.getId())));
            fields.add(new Field("title", List.of(doc.getTitle())));
            fields.add(new Field("content", List.of(doc.getContent())));
            fields.add(new Field("category", List.of(doc.getCategory() != null ? doc.getCategory() : "")));
            fields.add(new Field("keywords", List.of(doc.getKeywords() != null ? doc.getKeywords() : "")));
            fields.add(new Field("effective_at", List.of(doc.getEffectiveAt())));
            fields.add(new Field("expire_at", List.of(doc.getExpireAt())));
            // FloatVector 字段
            List<List<Float>> vecList = new ArrayList<>();
            List<Float> floatVec = new ArrayList<>(DIMENSIONS);
            for (float v : vec) floatVec.add(v);
            vecList.add(floatVec);
            fields.add(new Field("embedding", vecList));
            fields.add(new Field("created_at", List.of(System.currentTimeMillis())));
            // ⭐ 生产级 6 类字段
            fields.add(new Field("tenant_id", List.of(doc.getTenantId() != null && !doc.getTenantId().isBlank() ? doc.getTenantId() : "public")));
            fields.add(new Field("version", List.of(doc.getVersion() != null ? doc.getVersion() : "v1")));
            fields.add(new Field("source_url", List.of(doc.getSourceUrl() != null ? doc.getSourceUrl() : "")));
            fields.add(new Field("chunk_index", List.of((long) doc.getChunkIndex())));
            fields.add(new Field("updated_at", List.of(System.currentTimeMillis())));

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(fields)
                    .build();

            R<MutationResult> insertResp = client.insert(insertParam);
            if (insertResp.getStatus() == 0) {
                log.debug("[MilvusKB:{}] 文档已添加: {}", name, doc.getId());
            } else {
                log.warn("[MilvusKB:{}] 添加文档失败: {} - {}", name, doc.getId(), insertResp.getMessage());
            }
        } catch (Exception e) {
            log.warn("[MilvusKB:{}] 添加文档异常: {} - {}", name, doc.getId(), e.getMessage());
        }
    }

    @Override
    public void removeDocument(String id) {
        try {
            long milvusId = hashToLong(id);
            client.delete(DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr("id in [" + milvusId + "]")
                    .build());
        } catch (Exception e) {
            log.warn("[MilvusKB:{}] 删除文档失败: {} - {}", name, id, e.getMessage());
        }
    }

    @Override
    public void removeByBaseDocId(String baseDocId) {
        if (baseDocId == null || baseDocId.isBlank()) return;
        try {
            String expr = "doc_id like \"" + escapeMilvusStr(baseDocId) + "-%\" "
                    + "or doc_id == \"" + escapeMilvusStr(baseDocId) + "\"";
            client.delete(DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr(expr)
                    .build());
            log.info("[MilvusKB:{}] 按 baseDocId 删除: baseId={}", name, baseDocId);
        } catch (Exception e) {
            log.warn("[MilvusKB:{}] 按 baseDocId 删除失败: {} - {}", name, baseDocId, e.getMessage());
        }
    }

    /** Milvus 字符串转义（防注入） */
    private static String escapeMilvusStr(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK) {
        return search(query, topK, KnowledgeBase.PUBLIC_TENANT);
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK, String tenantId) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        int k = (topK > 0) ? topK : 5;

        float[] queryVec = embeddingModel.embedding(query);
        if (queryVec == null) {
            log.warn("[MilvusKB:{}] 嵌入不可用，降级到空结果", name);
            return Collections.emptyList();
        }

        // 构造 float[] 查询向量
        List<List<Float>> queryVectors = new ArrayList<>();
        List<Float> floatVec = new ArrayList<>(DIMENSIONS);
        for (float v : queryVec) floatVec.add(v);
        queryVectors.add(floatVec);

        try {
            // 🔴 ACL 检索前过滤：构建 filter expression
            String expr = buildAclExpr(tenantId);

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withMetricType(MetricType.COSINE)
                    .withOutFields(List.of("doc_id", "title", "content", "category",
                            "keywords", "effective_at", "expire_at", "created_at",
                            "tenant_id", "version", "source_url", "chunk_index", "updated_at"))
                    .withTopK(k)
                    .withVectors(queryVectors)
                    .withParams(SEARCH_PARAM)
                    .withExpr(expr)
                    .build();

            R<SearchResults> resp = client.search(searchParam);
            if (resp.getStatus() != 0) {
                log.warn("[MilvusKB:{}] 搜索失败: {}", name, resp.getMessage());
                return Collections.emptyList();
            }

            SearchResults results = resp.getData();
            if (results == null || results.getResults() == null
                    || results.getResults().getFieldsDataCount() == 0) {
                log.warn("[MilvusKB:{}] 搜索结果为空", name);
                return Collections.emptyList();
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(results.getResults());
            List<KnowledgeHit> hits = new ArrayList<>();
            int rowCount = wrapper.getIDScore(0).size();

            for (int i = 0; i < rowCount; i++) {
                double score;
                try {
                    score = wrapper.getIDScore(0).get(i).getScore();
                } catch (Exception e) {
                    continue;
                }
                if (score < MIN_SIMILARITY) continue;

                String docId = safeGetString(wrapper, "doc_id", i);
                String title = safeGetString(wrapper, "title", i);
                String content = safeGetString(wrapper, "content", i);
                String category = safeGetString(wrapper, "category", i);
                String keywords = safeGetString(wrapper, "keywords", i);
                long effectiveAt = safeGetLong(wrapper, "effective_at", i);
                long expireAt = safeGetLong(wrapper, "expire_at", i);

                KnowledgeDocument doc = new KnowledgeDocument(
                        docId, title, content, category, keywords, effectiveAt, expireAt);
                if (!doc.isActive()) continue;

                double finalScore = composeScore(score, doc, query);
                if (finalScore >= MIN_SIMILARITY) {
                    hits.add(new KnowledgeHit(doc, finalScore));
                }
            }

            hits.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            return hits.stream().limit(k).collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("[MilvusKB:{}] 搜索异常: {}", name, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public int size() {
        try {
            R<QueryResults> resp = client.query(io.milvus.param.dml.QueryParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withOutFields(List.of("count(*)"))
                    .withExpr("id >= 0")
                    .build());
            if (resp.getStatus() == 0 && resp.getData() != null) {
                return resp.getData().getFieldsDataCount();
            }
        } catch (Exception e) {
            log.warn("[MilvusKB:{}] 获取数量失败: {}", name, e.getMessage());
        }
        return 0;
    }

    @Override
    public void reindex() {
        log.info("[MilvusKB:{}] Milvus 自动维护索引，无需手动 rebuild", name);
    }

    /** 释放连接 */
    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("[MilvusKB:{}] 关闭连接异常: {}", name, e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    /** 安全获取字符串字段 */
    private static String safeGetString(SearchResultsWrapper wrapper, String field, int rowId) {
        try {
            List<?> data = wrapper.getFieldData(field, rowId);
            if (data != null && !data.isEmpty() && data.get(0) != null) {
                return data.get(0).toString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** 安全获取 long 字段 */
    private static long safeGetLong(SearchResultsWrapper wrapper, String field, int rowId) {
        try {
            List<?> data = wrapper.getFieldData(field, rowId);
            if (data != null && !data.isEmpty() && data.get(0) != null) {
                Object val = data.get(0);
                if (val instanceof Number) return ((Number) val).longValue();
                return Long.parseLong(val.toString());
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private double composeScore(double cosSim, KnowledgeDocument doc, String query) {
        double timeDecay = 1.0;
        if (doc.getExpireAt() > 0) {
            long daysToExpire = (doc.getExpireAt() - System.currentTimeMillis()) / 86400000;
            if (daysToExpire > 0) {
                timeDecay = Math.exp(-0.01 * (365 - daysToExpire));
            }
        }
        // 版本优先级
        double versionBoost = 1.0 + doc.getVersionPriority() * 0.1;
        double bm25Score = 0;
        if (bm25Scorer != null && bm25Scorer.isInitialized()) {
            bm25Score = Math.tanh(bm25Scorer.score(doc, query));
            bm25Score = Math.min(bm25Score, 1.0);
        }
        return cosSim * timeDecay * versionBoost * (1 - BM25_MIX_WEIGHT) + bm25Score * BM25_MIX_WEIGHT;
    }

    // ==================== ACL 辅助方法 ====================

    /**
     * 构建 Milvus 检索过滤表达式。
     * 检索前过滤：仅返回 tenant_id 为空（公开）或与请求 tenantId 匹配的文档。
     * <p>
     * Milvus 表达式语法约定：
     * - 字符串值用双引号
     * - in 操作符用于多值匹配
     * - VarChar 空字符串用 "" 表示
     * </p>
     */
    private static String buildAclExpr(String tenantId) {
        if (tenantId == null || tenantId.isEmpty()) {
            // 请求公开数据：只返回 tenant_id == "" 的文档
            return "tenant_id == \"\"";
        }
        // 请求特定租户：返回公开文档 + 该租户文档
        String safeTenant = tenantId.replace("\"", "\\\"");
        return "tenant_id in [\"\", \"" + safeTenant + "\"]";
    }

    // ==================== 哈希辅助方法 ====================

    /**
     * 将字符串映射为 64 位哈希（用于 Milvus Int64 主键）。
     * <p>
     * 使用 SHA-256 取前 8 字节，碰撞概率远低于 hashCode()。
     * 对于 10 万条文档，碰撞概率约 2.7×10⁻¹⁰。
     * </p>
     */
    private static long hashToLong(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // 取前 8 字节构造 long
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFF);
            }
            return hash == Long.MIN_VALUE ? 0 : Math.abs(hash);
        } catch (NoSuchAlgorithmException e) {
            // fallback: 原始 hashCode（极低概率触发）
            return Math.abs((long) input.hashCode());
        }
    }
}
