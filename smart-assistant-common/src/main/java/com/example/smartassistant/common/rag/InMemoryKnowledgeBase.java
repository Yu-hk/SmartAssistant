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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于 BGE 嵌入的内存知识库——文档存储于内存，使用 BGE 向量进行余弦相似度检索。
 * <p>
 * 适用场景：中小规模知识库（<1000 文档），Embedding 服务可用时自动启用。
 * 参考 RAG 文章的两阶段架构：先向量粗筛(Top-50)，再按 BM25+时效性精排(Top-5)。
 * </p>
 */
public class InMemoryKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(InMemoryKnowledgeBase.class);

    private final String name;
    private final BgeEmbeddingModel embeddingModel;

    /** 文档存储 id → doc */
    private final Map<String, KnowledgeDocument> docs = new ConcurrentHashMap<>();

    /** 文档 id → 嵌入向量 */
    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();

    /** 粗筛 Top-K */
    private static final int ROUGH_TOP_K = 50;

    /** 返回 Top-K */
    private static final int DEFAULT_TOP_K = 5;

    /** 时间衰减 λ（知识类取 0.01，新闻类取 0.1） */
    private static final double TIME_DECAY_LAMBDA = 0.01;

    /** BM25 关键词加分权重（与 BM25 分数的混合比例，0=不使用 BM25） */
    private static final double BM25_MIX_WEIGHT = 0.3;

    /** 版本优先级衰减权重（同内容但旧版本文档的分数折扣） */
    private static final double VERSION_PENALTY_RATE = 0.1;

    /** 余弦相似度阈值（低于此值不返回） */
    private static final double MIN_SIMILARITY = 0.30;

    /** BM25 评分器（HanLP 中文分词） */
    private Bm25Scorer bm25Scorer;

    /** 重排序器（Cross-Encoder，可选） */
    private Reranker reranker;

    /**
     * @param name           知识库名称
     * @param embeddingModel BGE 嵌入模型
     * @param tokenizer      HanLP 中文分词器（用于 BM25，可为 null 则跳过 BM25）
     * @param reranker       Cross-Encoder 重排序器（可为 null 则跳过）
     */
    public InMemoryKnowledgeBase(String name, BgeEmbeddingModel embeddingModel,
                                  ChineseTokenizer tokenizer, Reranker reranker) {
        this.name = name;
        this.embeddingModel = embeddingModel;
        if (tokenizer != null) {
            this.bm25Scorer = new Bm25Scorer(tokenizer);
        }
        this.reranker = reranker != null ? reranker : Reranker.identity();
    }

    @Override
    public String getName() { return name; }

    @Override
    public void addDocument(KnowledgeDocument doc) {
        if (doc == null) return;
        docs.put(doc.getId(), doc);
        // 计算 embedding
        float[] vec = embeddingModel.embedding(doc.toEmbedText());
        if (vec != null) {
            vectors.put(doc.getId(), normalize(vec));
        }
        log.info("[KnowledgeBase:{}] 添加文档: id={}, title={}", name, doc.getId(), doc.getTitle());
    }

    @Override
    public void removeDocument(String id) {
        docs.remove(id);
        vectors.remove(id);
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK) {
        return search(query, topK, KnowledgeBase.PUBLIC_TENANT);
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK, String tenantId) {
        if (query == null || query.isBlank() || docs.isEmpty()) return Collections.emptyList();
        int k = (topK > 0) ? topK : DEFAULT_TOP_K;

        // Stage 1: 向量粗筛 (Bi-Encoder)
        float[] queryVec = embeddingModel.embedding(query);
        if (queryVec == null) {
            log.warn("[KnowledgeBase:{}] 嵌入服务不可用，降级到关键词匹配", name);
            return fallbackKeywordSearch(query, k, tenantId);
        }
        queryVec = normalize(queryVec);

        List<ScoredDoc> roughResults = new ArrayList<>();
        for (var entry : vectors.entrySet()) {
            KnowledgeDocument doc = docs.get(entry.getKey());
            if (doc == null || !doc.isActive()) continue;

            // 🔴 ACL 检索前过滤：仅返回匹配 tenantId 或公开文档
            if (!tenantMatches(doc, tenantId)) continue;

            double cosSim = cosineSimilarity(queryVec, entry.getValue());
            if (cosSim < MIN_SIMILARITY) continue;

            // Stage 2: 精排 — 组合评分
            double finalScore = composeScore(cosSim, doc, query);
            roughResults.add(new ScoredDoc(doc, finalScore));
        }

        // 排序取 Top-K（粗排结果用于 Reranker）
        roughResults.sort((a, b) -> Double.compare(b.score, a.score));
        List<KnowledgeHit> hits = roughResults.stream()
                .limit(Math.max(k, 20)) // 给 Reranker 留更多候选
                .map(sd -> new KnowledgeHit(sd.doc, sd.score))
                .collect(Collectors.toList());

        // Stage 3: Cross-Encoder 重排序（可选）
        if (reranker != null && reranker != Reranker.identity()) {
            hits = reranker.rerank(hits, query, k);
        } else {
            hits = hits.size() <= k ? hits : hits.subList(0, k);
        }
        return hits;
    }

    @Override
    public int size() { return docs.size(); }

    @Override
    public void reindex() {
        vectors.clear();

        // ★ 清理被新版本取代的旧文档
        List<KnowledgeDocument> allDocs = new ArrayList<>(docs.values());
        removeSuperseded(allDocs);

        for (KnowledgeDocument doc : allDocs) {
            float[] vec = embeddingModel.embedding(doc.toEmbedText());
            if (vec != null) vectors.put(doc.getId(), normalize(vec));
        }
        // ★ 重新初始化 BM25 索引
        if (bm25Scorer != null) {
            bm25Scorer.initialize(allDocs);
            log.info("[KnowledgeBase:{}] BM25 索引完成: {} 篇文档, avgDocLen={:.1f}",
                    name, allDocs.size(),
                    bm25Scorer.isInitialized() ? (double) allDocs.stream()
                            .mapToInt(d -> (d.getContent() != null ? d.getContent().length() : 0)).average().orElse(0)
                            : 0);
        }
        log.info("[KnowledgeBase:{}] 重新索引完成: {} 篇文档", name, docs.size());
    }

    /**
     * 清理被新版本取代的旧文档。
     * 同 baseDocId 且版本更低的文档会被移除。
     */
    private void removeSuperseded(List<KnowledgeDocument> allDocs) {
        // 按 baseDocId 分组
        Map<String, List<KnowledgeDocument>> grouped = allDocs.stream()
                .collect(Collectors.groupingBy(KnowledgeDocument::getBaseDocId));

        for (var entry : grouped.entrySet()) {
            List<KnowledgeDocument> group = entry.getValue();
            if (group.size() <= 1) continue;
            // 找出版本最高的文档
            KnowledgeDocument newest = group.stream()
                    .max(Comparator.comparingDouble(KnowledgeDocument::getVersionPriority))
                    .orElse(null);
            if (newest == null) continue;
            // 移除所有被 newest 取代的文档
            for (KnowledgeDocument doc : group) {
                if (doc != newest && doc.isSupersededBy(newest)) {
                    log.info("[KnowledgeBase:{}] 清理旧版本: baseId={}, old={}, new={}",
                            name, entry.getKey(), doc.getVersion(), newest.getVersion());
                    docs.remove(doc.getId());
                }
            }
        }
    }

    // ==================== 精排 ====================

    /**
     * 组合评分 = 余弦相似度 × 时间衰减 × 版本优先级 + BM25 × 混合权重
     * <p>
     * 版本优先级：v1 为 1.0，v2 为 1.1，v3 为 1.2（更高版本优先）。
     * 被同一 baseId 的新版本取代的文档会被降低分数。
     * </p>
     */
    private double composeScore(double cosSim, KnowledgeDocument doc, String query) {
        // 时间衰减
        double timeDecay = 1.0;
        if (doc.getExpireAt() > 0) {
            long daysToExpire = (doc.getExpireAt() - System.currentTimeMillis()) / 86400000;
            if (daysToExpire > 0) {
                timeDecay = Math.exp(-TIME_DECAY_LAMBDA * (365 - daysToExpire));
            }
        }

        // 版本优先级（v1=1.0, v2=1.1, v3=1.2）
        double versionPriority = 1.0 + doc.getVersionPriority() * VERSION_PENALTY_RATE;
        // 如果还有其他版本的文档，检查是否被取代
        double versionBoost = versionPriority;

        // BM25 分数（标准化到 0~1 范围）
        double bm25Score = 0;
        if (bm25Scorer != null && bm25Scorer.isInitialized()) {
            bm25Score = Math.tanh(bm25Scorer.score(doc, query)); // tanh 压缩到 [0,1)
            bm25Score = Math.min(bm25Score, 1.0); // 上限保护
        }

        return cosSim * timeDecay * versionBoost * (1 - BM25_MIX_WEIGHT) + bm25Score * BM25_MIX_WEIGHT;
    }

    // ==================== 兜底搜索 ====================

    private List<KnowledgeHit> fallbackKeywordSearch(String query, int topK, String tenantId) {
        String q = query.toLowerCase();
        return docs.values().stream()
                .filter(KnowledgeDocument::isActive)
                .filter(doc -> tenantMatches(doc, tenantId))
                .map(doc -> {
                    double score = 0;
                    if (doc.getTitle().toLowerCase().contains(q)) score += 0.5;
                    if (doc.getContent().toLowerCase().contains(q)) score += 0.3;
                    if (doc.getKeywords() != null && doc.getKeywords().toLowerCase().contains(q)) score += 0.2;
                    return new KnowledgeHit(doc, score);
                })
                .filter(hit -> hit.getScore() > 0)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .collect(Collectors.toList());
    }

    // ==================== 工具方法 ====================

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private static float[] normalize(float[] vec) {
        double norm = 0;
        for (float v : vec) norm += (double) v * v;
        norm = Math.sqrt(norm);
        if (norm == 0) return vec;
        float[] result = new float[vec.length];
        for (int i = 0; i < vec.length; i++) result[i] = (float) (vec[i] / norm);
        return result;
    }

    private static class ScoredDoc {
        final KnowledgeDocument doc;
        final double score;
        ScoredDoc(KnowledgeDocument doc, double score) { this.doc = doc; this.score = score; }
    }

    // ==================== ACL 辅助方法 ====================

    /**
     * 检查文档是否对指定租户可见。
     * 文档 tenantId 为空（公开）或与请求的 tenantId 匹配时返回 true。
     */
    private static boolean tenantMatches(KnowledgeDocument doc, String requestTenantId) {
        String docTenant = doc.getTenantId();
        // 公开文档对所有用户可见
        if (docTenant == null || docTenant.isEmpty()) return true;
        // 请求为空时只返回公开文档
        if (requestTenantId == null || requestTenantId.isEmpty()) return false;
        // 精确匹配租户
        return docTenant.equals(requestTenantId);
    }
}
