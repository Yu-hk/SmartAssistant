/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ⭐ 纯 Java BM25 文本检索评分器。
 * <p>
 * 实现 BM25 算法（Okapi BM25），用于评估查询与文档的相关性。
 * 替代 PostgreSQL ts_rank 或简单关键词匹配。
 * </p>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>k1</b> = 1.5：词频饱和参数，控制词频对分数的影响</li>
 *   <li><b>b</b> = 0.75：长度归一化参数，控制文档长度对分数的影响</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>
 * Bm25Scorer scorer = new Bm25Scorer(documents);
 * double score = scorer.score("iPhone 价格", "iPhone 15 Pro 售价 8999 元");
 * </pre>
 */
@Component
public class Bm25Scorer {

    private static final Logger log = LoggerFactory.getLogger(Bm25Scorer.class);

    /** 词频饱和参数 */
    private static final double K1 = 1.5;

    /** 长度归一化参数 */
    private static final double B = 0.75;

    /** 文档集合（用于计算 IDF 和 avgDL） */
    private List<String> documentCollection;

    /** 文档集合中的总文档数 */
    private int totalDocs;

    /** 包含每个词的文档数（用于 IDF 计算） */
    private Map<String, Integer> docFrequency;

    /** 平均文档长度 */
    private double avgDocLength;

    public Bm25Scorer() {
        this.documentCollection = new ArrayList<>();
        this.docFrequency = new HashMap<>();
    }

    /**
     * 初始化文档集合，预计算 IDF 和平均长度。
     *
     * @param documents 文档文本列表
     */
    public synchronized void initialize(List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            log.warn("[BM25] 文档集合为空");
            return;
        }
        this.documentCollection = new ArrayList<>(documents);
        this.totalDocs = documents.size();
        this.docFrequency = new HashMap<>();
        double totalLength = 0;

        for (String doc : documents) {
            if (doc == null || doc.isBlank()) continue;
            List<String> tokens = tokenize(doc);
            totalLength += tokens.size();

            // 统计每个词出现在多少文档中
            Set<String> uniqueTerms = new HashSet<>(tokens);
            for (String term : uniqueTerms) {
                docFrequency.merge(term, 1, Integer::sum);
            }
        }

        this.avgDocLength = totalDocs > 0 ? totalLength / totalDocs : 1;
        log.info("[BM25] 初始化完成: {} 个文档, avgDL={}", totalDocs, String.format("%.2f", avgDocLength));
    }

    /**
     * 计算查询与单个文档的 BM25 分数。
     */
    public double score(String query, String document) {
        if (query == null || query.isBlank() || document == null) {
            return 0.0;
        }

        List<String> queryTokens = tokenize(query);
        List<String> docTokens = tokenize(document);
        if (queryTokens.isEmpty()) return 0.0;

        double score = 0.0;
        double docLen = docTokens.size();

        // 统计文档中各词频
        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : docTokens) {
            termFreq.merge(token, 1, Integer::sum);
        }

        for (String term : queryTokens) {
            int tf = termFreq.getOrDefault(term, 0);
            if (tf == 0) continue;

            // IDF = ln(1 + (N - df + 0.5) / (df + 0.5))
            int df = docFrequency.getOrDefault(term, 1);
            double idf = Math.log(1.0 + (totalDocs - df + 0.5) / (df + 0.5));

            // BM25 核心公式
            double numerator = tf * (K1 + 1);
            double denominator = tf + K1 * (1 - B + B * docLen / avgDocLength);
            score += idf * numerator / denominator;
        }

        return score;
    }

    /**
     * 对多个文档按 BM25 分数排序。
     *
     * @param query     查询文本
     * @param documents 待评分的文档列表（Map: docId → docText）
     * @return 按 BM25 分数降序排列的 (docId, score) 列表
     */
    public List<Map.Entry<String, Double>> rank(String query, Map<String, String> documents) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return List.of();
        }

        List<Map.Entry<String, Double>> scored = documents.entrySet().stream()
                .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), score(query, e.getValue())))
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());

        log.debug("[BM25] 排序完成: query={}, candidates={}, top={}",
                query, documents.size(), scored.size() > 0 ? String.format("%s=%.4f", scored.get(0).getKey(), scored.get(0).getValue()) : "none");
        return scored;
    }

    /**
     * 简单中文/英文分词（按字符和空格切分）。
     * <p>
     * 中文按字切分（单字作为 token），英文按空格切分。
     * </p>
     */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) return tokens;

        // 按非字母数字字符分割
        StringBuilder current = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                current.append(c);
            } else {
                if (current.length() > 0) {
                    tokens.add(current.toString().toLowerCase());
                    current = new StringBuilder();
                }
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString().toLowerCase());
        }

        // 中文：每个中文字符单独作为一个 token（对短文本 BM25 效果更好）
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            if (token.length() > 1 && token.chars().anyMatch(c -> c > 0x4E00)) {
                // 含中文的 token 按字拆分
                for (char c : token.toCharArray()) {
                    if (c > 0x4E00) {
                        result.add(String.valueOf(c));
                    }
                }
                // 也保留原词（对精确匹配有用）
                result.add(token);
            } else {
                result.add(token);
            }
        }

        return result;
    }
}
