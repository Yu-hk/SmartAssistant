/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;

import java.util.*;
import java.util.stream.Collectors;

/**
 * BM25 评分器——基于 HanLP 中文分词的 BM25 算法实现。
 * <p>
 * 替换 {@link InMemoryKnowledgeBase} 中硬编码的 {@code BM25_BONUS} 关键词命中加分，
 * 使用标准 BM25 公式计算文档与查询的相关性。
 * </p>
 *
 * <h3>BM25 公式</h3>
 * <pre>
 * score(D, Q) = Σ (IDF(t) × TF(t, D) × (k1 + 1) / (TF(t, D) + k1 × (1 - b + b × |D| / avgdl)))
 * IDF(t) = log((N - n(t) + 0.5) / (n(t) + 0.5))
 * </pre>
 *
 * <p>参数：k1=1.5（词频饱和），b=0.75（长度归一化）</p>
 */
public class Bm25Scorer {

    /** BM25 词频饱和参数 */
    private static final double K1 = 1.5;

    /** BM25 长度归一化参数 */
    private static final double B = 0.75;

    private final ChineseTokenizer tokenizer;

    /** 文档总数 N */
    private int totalDocs;

    /** 包含该词的文档数 n(t) → 用于计算 IDF */
    private Map<String, Integer> docFreq;

    /** 各文档的 token 列表缓存 */
    private Map<String, List<String>> docTokens;

    /** 各文档的长度（token 数） */
    private Map<String, Integer> docLengths;

    /** 平均文档长度 */
    private double avgDocLength;

    /** 是否已初始化 */
    private boolean initialized = false;

    public Bm25Scorer(ChineseTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * 基于文档集合初始化 BM25 参数（计算 IDF、平均文档长度）。
     * 在文档集合变更后（如 reindex 时）需重新调用。
     */
    public void initialize(List<KnowledgeDocument> docs) {
        this.totalDocs = docs.size();
        this.docFreq = new HashMap<>();
        this.docTokens = new HashMap<>();
        this.docLengths = new HashMap<>();
        this.avgDocLength = 0;

        if (totalDocs == 0) {
            this.initialized = true;
            return;
        }

        long totalLength = 0;
        for (KnowledgeDocument doc : docs) {
            List<String> tokens = tokenize(doc.toEmbedText());
            docTokens.put(doc.getId(), tokens);
            docLengths.put(doc.getId(), tokens.size());
            totalLength += tokens.size();

            // 统计词频（每个文档只计一次，用于 IDF）
            Set<String> uniqueTokens = new HashSet<>(tokens);
            for (String token : uniqueTokens) {
                docFreq.merge(token, 1, Integer::sum);
            }
        }
        this.avgDocLength = (double) totalLength / totalDocs;
        this.initialized = true;
    }

    /**
     * 计算查询与文档的 BM25 相关性分数。
     *
     * @param doc   知识文档
     * @param query 用户查询
     * @return BM25 分数（0.0 ~ N，通常不超过 10）
     */
    public double score(KnowledgeDocument doc, String query) {
        if (!initialized || doc == null || query == null || query.isBlank()) return 0;

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) return 0;

        List<String> docTokenList = docTokens.get(doc.getId());
        if (docTokenList == null) return 0;

        int docLength = docLengths.getOrDefault(doc.getId(), 0);
        if (docLength == 0) return 0;

        // 计算文档中各 token 的 TF
        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : docTokenList) {
            termFreq.merge(token, 1, Integer::sum);
        }

        double score = 0;
        for (String qt : queryTokens) {
            int tf = termFreq.getOrDefault(qt, 0);
            if (tf == 0) continue;

            // IDF(t) = log((N - n(t) + 0.5) / (n(t) + 0.5))
            int n = docFreq.getOrDefault(qt, 0);
            double idf = Math.log((totalDocs - n + 0.5) / (n + 0.5));

            // BM25 核心公式
            double numerator = tf * (K1 + 1);
            double denominator = tf + K1 * (1 - B + B * docLength / avgDocLength);
            score += idf * numerator / denominator;
        }

        return score;
    }

    /**
     * 对候选文档按 BM25 分数排序，返回前缀的文档。
     *
     * @param candidates 候选文档列表
     * @param query      用户查询
     * @param topK       返回条数
     * @return 按 BM25 降序排列的文档（含评分）
     */
    public List<Map.Entry<KnowledgeDocument, Double>> rerank(
            List<KnowledgeDocument> candidates, String query, int topK) {
        return candidates.stream()
                .map(doc -> Map.entry(doc, score(doc, query)))
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .collect(Collectors.toList());
    }

    /** 是否已初始化 */
    public boolean isInitialized() { return initialized; }

    /** 获取文档数 */
    public int getTotalDocs() { return totalDocs; }

    /** 对文本进行中文分词 */
    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        Set<String> tokens = tokenizer.tokenize(text);
        return new ArrayList<>(tokens);
    }
}
