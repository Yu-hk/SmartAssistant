/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.experience;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Milvus 的经验向量检索服务 — 替代 {@link ExperienceEmbeddingMapper} 的 pgvector 方案。
 * <p>
 * 提供经验向量的增删查能力，使用 COSINE 相似度 + IVF_FLAT 索引。
 * </p>
 *
 * <p>Milvus Collection 设计：</p>
 * <pre>
 * Collection: experience_embeddings
 * ├── id        (Int64, 主键, auto_id=false)
 * ├── exp_id    (VarChar, 经验 ID, maxLength=128)
 * ├── agent_name (VarChar, maxLength=64)
 * ├── intent_tag (VarChar, maxLength=64)
 * ├── embedding (FloatVector, dim=384)
 * └── created_at (Int64)
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "app.milvus.enabled", havingValue = "true")
public class ExperienceMilvusService {

    private static final Logger log = LoggerFactory.getLogger(ExperienceMilvusService.class);

    private static final String COLLECTION = "experience_embeddings";
    private static final int DIMENSIONS = 384;
    private static final String INDEX_PARAM = "{\"nlist\": 128}";
    private static final String SEARCH_PARAM = "{\"nprobe\": 16}";

    private final MilvusServiceClient client;

    public ExperienceMilvusService(
            @Value("${app.milvus.host:localhost}") String host,
            @Value("${app.milvus.port:19530}") int port) {
        this.client = new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(host)
                        .withPort(port)
                        .withConnectTimeout(5000, TimeUnit.MILLISECONDS)
                        .build());
        initCollection();
    }

    private void initCollection() {
        try {
            R<Boolean> hasResp = client.hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
            if (hasResp.getStatus() != 0) {
                log.warn("[ExpMilvus] 连接检查失败: {}", hasResp.getMessage());
                return;
            }
            if (hasResp.getData()) {
                client.loadCollection(LoadCollectionParam.newBuilder()
                        .withCollectionName(COLLECTION).build());
                log.info("[ExpMilvus] Collection 已存在");
                return;
            }

            // 创建 Collection
            client.createCollection(CreateCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withDescription("Experience embeddings for SmartAssistant")
                    .addFieldType(FieldType.newBuilder()
                            .withName("id").withDataType(DataType.Int64)
                            .withPrimaryKey(true).withAutoID(false).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("exp_id").withDataType(DataType.VarChar).withMaxLength(128).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("agent_name").withDataType(DataType.VarChar).withMaxLength(64).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("intent_tag").withDataType(DataType.VarChar).withMaxLength(64).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("embedding").withDataType(DataType.FloatVector).withDimension(DIMENSIONS).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("created_at").withDataType(DataType.Int64).build())
                    .build());

            // 创建索引
            client.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam(INDEX_PARAM)
                    .build());

            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION).build());
            log.info("[ExpMilvus] Collection 已创建");
        } catch (Exception e) {
            log.warn("[ExpMilvus] 初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 插入或更新经验向量。
     */
    public void upsert(String expId, String agentName, String intentTag, List<Float> embedding) {
        try {
            long milvusId = Math.abs((long) expId.hashCode());
            List<Field> fields = new ArrayList<>();
            fields.add(new Field("id", List.of(milvusId)));
            fields.add(new Field("exp_id", List.of(expId)));
            fields.add(new Field("agent_name", List.of(agentName)));
            fields.add(new Field("intent_tag", List.of(intentTag)));
            fields.add(new Field("embedding", List.of(embedding)));
            fields.add(new Field("created_at", List.of(System.currentTimeMillis())));

            client.insert(InsertParam.newBuilder()
                    .withCollectionName(COLLECTION).withFields(fields).build());
            log.debug("[ExpMilvus] upsert 完成: expId={}", expId);
        } catch (Exception e) {
            log.warn("[ExpMilvus] upsert 失败: {} - {}", expId, e.getMessage());
        }
    }

    /**
     * 删除一条经验向量。
     */
    public void delete(String expId) {
        try {
            long milvusId = Math.abs((long) expId.hashCode());
            client.delete(DeleteParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withExpr("id in [" + milvusId + "]")
                    .build());
        } catch (Exception e) {
            log.warn("[ExpMilvus] delete 失败: {} - {}", expId, e.getMessage());
        }
    }

    /**
     * 余弦相似度搜索 — 返回最相似的 N 条经验。
     */
    public List<SearchResult> findSimilar(List<Float> queryVec, double maxDistance, int limit) {
        try {
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withMetricType(MetricType.COSINE)
                    .withOutFields(List.of("exp_id", "agent_name"))
                    .withTopK(limit)
                    .withVectors(List.of(queryVec))
                    .withParams(SEARCH_PARAM)
                    .build();

            R<SearchResults> resp = client.search(searchParam);
            if (resp.getStatus() != 0) {
                log.warn("[ExpMilvus] 搜索失败: {}", resp.getMessage());
                return Collections.emptyList();
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
            List<SearchResult> results = new ArrayList<>();
            int rowCount = wrapper.getIDScore(0).size();
            for (int i = 0; i < rowCount; i++) {
                try {
                    double score = wrapper.getIDScore(0).get(i).getScore();
                    if ((1.0 - score) > maxDistance) continue;
                    List<?> expIdData = wrapper.getFieldData("exp_id", i);
                    List<?> agentData = wrapper.getFieldData("agent_name", i);
                    if (expIdData == null || expIdData.isEmpty()) continue;
                    String expId = expIdData.get(0).toString();
                    String agentName = agentData != null && !agentData.isEmpty()
                            ? agentData.get(0).toString() : "";
                    results.add(new SearchResult(expId, agentName, score));
                } catch (Exception ignored) {}
            }
            return results;
        } catch (Exception e) {
            log.warn("[ExpMilvus] 搜索异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 释放连接 */
    public void close() {
        try { client.close(); } catch (Exception ignored) {}
    }

    public static class SearchResult {
        private final String expId;
        private final String agentName;
        private final double similarity;

        public SearchResult(String expId, String agentName, double similarity) {
            this.expId = expId;
            this.agentName = agentName;
            this.similarity = similarity;
        }

        public String getExpId() { return expId; }
        public String getAgentName() { return agentName; }
        public double getSimilarity() { return similarity; }
    }
}
