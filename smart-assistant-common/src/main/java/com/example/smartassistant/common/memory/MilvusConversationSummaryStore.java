/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.*;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.InsertParam.Field;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Milvus 对话摘要持久化存储 — 方案 A 实现。
 * <p>
 * 在 SmartReActAgent 每次 Context 压缩后，将 9 段式结构摘要
 * 嵌入为 BGE 向量并存入 Milvus，支持按用户语义检索历史摘要。
 * </p>
 *
 * <p>Milvus Collection 设计：</p>
 * <pre>
 * Collection: conversation_summaries
 * ├── id            (Int64, 主键, auto_id=true)
 * ├── user_id       (VarChar, maxLength=128)
 * ├── session_id    (VarChar, maxLength=128)
 * ├── agent_name    (VarChar, maxLength=64)
 * ├── summary_text  (VarChar, maxLength=65535)
 * ├── generation    (Int64)
 * ├── version       (VarChar, 32)    ← 🔴 版本：灰度回滚
 * ├── created_at    (Int64)
 * └── embedding     (FloatVector, dim=384)
 * Index: IVF_FLAT (nlist=128), metric=COSINE
 * </pre>
 */
public class MilvusConversationSummaryStore implements ConversationSummaryStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusConversationSummaryStore.class);

    private static final String COLLECTION = "conversation_summaries";
    private static final String INDEX_PARAM = "{\"nlist\": 128}";
    private static final String SEARCH_PARAM = "{\"nprobe\": 16}";

    private final MilvusServiceClient client;
    private final BgeEmbeddingModel embeddingModel;
    private volatile boolean initialized = false;

    public MilvusConversationSummaryStore(String host, int port, BgeEmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
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
                log.warn("[SummaryStore] Milvus 连接失败: {}", hasResp.getMessage());
                return;
            }
            if (hasResp.getData()) {
                client.loadCollection(
                        LoadCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
                initialized = true;
                log.info("[SummaryStore] Collection 已存在");
                return;
            }

            client.createCollection(CreateCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withDescription("SmartAssistant conversation summaries")
                    .addFieldType(FieldType.newBuilder()
                            .withName("id").withDataType(DataType.Int64)
                            .withPrimaryKey(true).withAutoID(true).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("user_id").withDataType(DataType.VarChar).withMaxLength(128).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("session_id").withDataType(DataType.VarChar).withMaxLength(128).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("agent_name").withDataType(DataType.VarChar).withMaxLength(64).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("summary_text").withDataType(DataType.VarChar).withMaxLength(65535).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("generation").withDataType(DataType.Int64).build())
                    // ⭐ 生产级版本字段
                    .addFieldType(FieldType.newBuilder()
                            .withName("version").withDataType(DataType.VarChar).withMaxLength(32).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("created_at").withDataType(DataType.Int64).build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("embedding").withDataType(DataType.FloatVector).withDimension(384).build())
                    .build());

            client.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam(INDEX_PARAM)
                    .build());

            client.loadCollection(
                    LoadCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
            initialized = true;
            log.info("[SummaryStore] Collection 已创建");
        } catch (Exception e) {
            log.warn("[SummaryStore] 初始化失败: {}", e.getMessage());
        }
    }

    @Override
    public void store(String userId, String sessionId, String agentName,
                      String summary, int generation) {
        if (summary == null || summary.isBlank()) return;
        if (!initialized) {
            log.warn("[SummaryStore] 未初始化，跳过存储");
            return;
        }

        try {
            float[] vec = embeddingModel.embedding(summary);
            if (vec == null) {
                log.warn("[SummaryStore] 嵌入不可用，跳过");
                return;
            }

            List<Float> floatVec = new ArrayList<>(384);
            for (float v : vec) floatVec.add(v);

            List<Field> fields = new ArrayList<>();
            fields.add(new Field("user_id", List.of(userId != null ? userId : "")));
            fields.add(new Field("session_id", List.of(sessionId != null ? sessionId : "")));
            fields.add(new Field("agent_name", List.of(agentName != null ? agentName : "")));
            fields.add(new Field("summary_text", List.of(summary)));
            fields.add(new Field("generation", List.of((long) generation)));
            fields.add(new Field("version", List.of("v1")));
            fields.add(new Field("created_at", List.of(System.currentTimeMillis())));
            fields.add(new Field("embedding", List.of(floatVec)));

            client.insert(InsertParam.newBuilder()
                    .withCollectionName(COLLECTION).withFields(fields).build());

            log.debug("[SummaryStore] 摘要已存储: agent={}, gen={}, len={}",
                    agentName, generation, summary.length());
        } catch (Exception e) {
            log.warn("[SummaryStore] 存储摘要失败: {}", e.getMessage());
        }
    }

    @Override
    public List<SummaryHit> search(String userId, String query, int topK) {
        if (query == null || query.isBlank() || !initialized) return List.of();
        int k = Math.max(1, Math.min(topK, 20));

        try {
            float[] queryVec = embeddingModel.embedding(query);
            if (queryVec == null) return List.of();

            List<Float> queryFloats = new ArrayList<>(384);
            for (float v : queryVec) queryFloats.add(v);

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withMetricType(MetricType.COSINE)
                    .withOutFields(List.of("summary_text", "generation", "created_at"))
                    .withTopK(k)
                    .withVectors(List.of(queryFloats))
                    .withParams(SEARCH_PARAM)
                    .build();

            R<SearchResults> resp = client.search(searchParam);
            if (resp.getStatus() != 0) return List.of();

            SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
            int rowCount = wrapper.getIDScore(0).size();
            List<SummaryHit> hits = new ArrayList<>();

            for (int i = 0; i < rowCount; i++) {
                try {
                    double score = wrapper.getIDScore(0).get(i).getScore();
                    String summary = safeGetField(wrapper, "summary_text", i);
                    int generation = (int) safeGetLong(wrapper, "generation", i);
                    long createdAt = safeGetLong(wrapper, "created_at", i);
                    if (summary != null && !summary.isBlank()) {
                        hits.add(new SummaryHit(summary, score, generation, createdAt));
                    }
                } catch (Exception e) {
                    log.warn("[MilvusSummary] 解析会话摘要行失败: {}", e.getMessage());
                }
            }
            return hits;
        } catch (Exception e) {
            log.warn("[SummaryStore] 检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 释放连接 */
    public void close() {
        try { client.close(); } catch (Exception ignored) {}
    }

    private static String safeGetField(SearchResultsWrapper wrapper, String field, int rowId) {
        try {
            List<?> data = wrapper.getFieldData(field, rowId);
            if (data != null && !data.isEmpty() && data.get(0) != null) {
                return data.get(0).toString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static long safeGetLong(SearchResultsWrapper wrapper, String field, int rowId) {
        try {
            List<?> data = wrapper.getFieldData(field, rowId);
            if (data != null && !data.isEmpty() && data.get(0) != null) {
                Object val = data.get(0);
                if (val instanceof Number) return ((Number) val).longValue();
                return Long.parseLong(val.toString());
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
