/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ⭐ 基于 BGE 的重排序器 — 对候选结果进行二次精排。
 * <p>
 * 策略：对每个 (query, doc) 对计算 refined score，重新排序。
 * 核心改进是"标题加权"：文档标题与 query 的相关性被赋予更高权重，
 * 因为标题通常更集中地反映了文档的核心内容。
 * </p>
 *
 * <p>评分公式：</p>
 * <pre>
 * refinedScore = titleCosine × 0.4 + fullCosine × 0.6
 * </pre>
 * 标题相似度权重 0.4，全文相似度权重 0.6。
 * 这比原始 {@link InMemoryKnowledgeBase#composeScore} 更细粒度，
 * 因为它分别计算了标题和全文的语义相似度。
 * </p>
 */
public class BgeReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(BgeReranker.class);

    /** 标题相似度权重（标题通常更集中反映文档核心内容） */
    private static final double TITLE_WEIGHT = 0.4;

    /** 全文相似度权重 */
    private static final double CONTENT_WEIGHT = 0.6;

    private final BgeEmbeddingModel embeddingModel;

    public BgeReranker(BgeEmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<KnowledgeHit> rerank(List<KnowledgeHit> hits, String query, int topK) {
        if (hits == null || hits.isEmpty()) return List.of();
        if (topK <= 0) topK = 5;

        long start = System.currentTimeMillis();

        // 计算 query 的 embedding（复用，只计算一次）
        float[] queryVec = embeddingModel.embedding(query);
        if (queryVec == null) {
            log.warn("[BgeReranker] 嵌入服务不可用，返回原始排序");
            return hits.size() <= topK ? hits : hits.subList(0, topK);
        }
        queryVec = normalize(queryVec);

        // 对每个候选计算 refined score
        List<ScoredHit> scored = new ArrayList<>();
        for (KnowledgeHit hit : hits) {
            KnowledgeDocument doc = hit.getDocument();
            if (doc == null) continue;

            // 文档标题的 embedding（标题更聚焦）
            float[] titleVec = embeddingModel.embedding(doc.getTitle());
            double titleSim = 0;
            if (titleVec != null) {
                titleSim = cosineSimilarity(queryVec, normalize(titleVec));
            }

            // 全文的 embedding
            float[] contentVec = embeddingModel.embedding(doc.toEmbedText());
            double contentSim = 0;
            if (contentVec != null) {
                contentSim = cosineSimilarity(queryVec, normalize(contentVec));
            }

            // 综合评分：标题加权
            double refinedScore = titleSim * TITLE_WEIGHT + contentSim * CONTENT_WEIGHT;

            scored.add(new ScoredHit(hit, refinedScore));

            log.trace("[BgeReranker] doc={}, titleSim={}, contentSim={}, refined={}",
                    doc.getId(), String.format("%.4f", titleSim),
                    String.format("%.4f", contentSim), String.format("%.4f", refinedScore));
        }

        // 按 refined score 降序排列
        scored.sort(Comparator.<ScoredHit>comparingDouble(s -> s.refinedScore).reversed());

        List<KnowledgeHit> reranked = scored.stream()
                .limit(topK)
                .map(s -> new KnowledgeHit(s.hit.getDocument(), s.refinedScore))
                .toList();

        long elapsed = System.currentTimeMillis() - start;
        log.debug("[BgeReranker] 重排完成: candidates={}, output={}, 耗时={}ms",
                hits.size(), reranked.size(), elapsed);

        return reranked;
    }

    // ==================== 工具方法 ====================

    private static float[] normalize(float[] vec) {
        double norm = 0;
        for (float v : vec) norm += (double) v * v;
        norm = Math.sqrt(norm);
        if (norm == 0) return vec;
        float[] result = new float[vec.length];
        for (int i = 0; i < vec.length; i++) result[i] = (float) (vec[i] / norm);
        return result;
    }

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

    /** 带分数的候选结果 */
    private record ScoredHit(KnowledgeHit hit, double refinedScore) {}
}
