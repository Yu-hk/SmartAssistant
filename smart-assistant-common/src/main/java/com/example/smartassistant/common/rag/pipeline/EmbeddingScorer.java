/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * 基于嵌入服务的重排序评分器。
 *
 * <p>通过远程嵌入服务计算 query 与文档的余弦相似度。
 * {@link Function}{@code <String, float[]>} 接收文本返回嵌入向量。
 * 适用于 {@link RerankHandler} 的 scorer 参数注入。
 *
 * <p>此设计不依赖具体嵌入 SDK，通过函数式接口解耦。
 */
public class EmbeddingScorer implements java.util.function.BiFunction<String, String, Double> {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingScorer.class);

    private final Function<String, float[]> embeddingFunc;

    public EmbeddingScorer(Function<String, float[]> embeddingFunc) {
        this.embeddingFunc = embeddingFunc;
    }

    @Override
    public Double apply(String query, String documentText) {
        if (query == null || query.isBlank() || documentText == null || documentText.isBlank()) {
            return 0.0;
        }

        try {
            float[] queryVec = normalize(embeddingFunc.apply(query));
            if (queryVec == null) return 0.5;

            float[] docVec = normalize(embeddingFunc.apply(documentText));
            if (docVec == null) return 0.5;

            return Math.max(0, Math.min(1, (cosineSimilarity(queryVec, docVec) + 1) / 2));
        } catch (Exception e) {
            log.warn("[EmbeddingScorer] 评分异常: {}", e.getMessage());
            return 0.5;
        }
    }

    // ==================== 向量工具 ====================

    private static float[] normalize(float[] vec) {
        if (vec == null) return null;
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
}
