/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiFunction;

/**
 * 基于 BGE 嵌入模型的重排序评分器。
 *
 * <p>使用 BGE 嵌入模型计算 query 与文档的余弦相似度作为相关性分数。
 * 适用于 {@link RerankHandler} 的 scorer 参数注入。
 *
 * <p>评分策略：分别计算文档标题（首行/前 50 字）和全文与 query 的相似度，
 * 按标题权重 0.4 + 全文权重 0.6 加权融合，参考 BgeReranker 的设计。
 */
public class BgeRerankScorer implements BiFunction<String, String, Double> {

    private static final Logger log = LoggerFactory.getLogger(BgeRerankScorer.class);

    /** 标题相似度权重 */
    private static final double TITLE_WEIGHT = 0.4;

    /** 全文相似度权重 */
    private static final double CONTENT_WEIGHT = 0.6;

    private final BgeEmbeddingModel embeddingModel;

    public BgeRerankScorer(BgeEmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public Double apply(String query, String documentText) {
        if (query == null || query.isBlank() || documentText == null || documentText.isBlank()) {
            return 0.0;
        }
        if (embeddingModel == null) {
            log.warn("[BgeRerankScorer] 嵌入模型未配置");
            return 0.5;
        }

        try {
            // 提取标题（首行或前 100 字符）
            String title = extractTitle(documentText);
            String content = documentText;

            // 计算 query 的 embedding
            float[] queryVec = normalize(embeddingModel.embedding(query));
            if (queryVec == null) return 0.5;

            // 标题相似度
            double titleSim = 0;
            if (!title.isEmpty() && !title.equals(content)) {
                float[] titleVec = normalize(embeddingModel.embedding(title));
                if (titleVec != null) {
                    titleSim = cosineSimilarity(queryVec, titleVec);
                }
            }

            // 全文相似度
            float[] contentVec = normalize(embeddingModel.embedding(content));
            double contentSim = 0;
            if (contentVec != null) {
                contentSim = cosineSimilarity(queryVec, contentVec);
            }

            // 加权融合
            double score;
            if (titleSim > 0 && contentSim > 0) {
                score = titleSim * TITLE_WEIGHT + contentSim * CONTENT_WEIGHT;
            } else if (contentSim > 0) {
                score = contentSim;
            } else {
                score = titleSim;
            }

            // 归一化到 0~1
            score = Math.max(0, Math.min(1, (score + 1) / 2));

            return score;

        } catch (com.example.smartassistant.common.error.AgentException e) {
            // 嵌入类可重试错误 → 向上冒泡，由管线漏斗统一分级
            throw e;
        } catch (Exception e) {
            log.warn("[BgeRerankScorer] 评分异常: {}", e.getMessage());
            return 0.5;
        }
    }

    /**
     * 从文档文本中提取标题。
     *
     * <p>策略：
     * <ol>
     *   <li>取第一行文本（换行符之前）</li>
     *   <li>如果第一行过长（>100 字符），取前 50 字</li>
     * </ol>
     */
    private String extractTitle(String text) {
        int newlineIdx = text.indexOf('\n');
        String firstLine = newlineIdx > 0 ? text.substring(0, newlineIdx).trim() : text.trim();
        if (firstLine.length() > 100) {
            return firstLine.substring(0, 50);
        }
        return firstLine;
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
