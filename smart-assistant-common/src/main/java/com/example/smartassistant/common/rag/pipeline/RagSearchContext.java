/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG 检索管线上下文，在各 {@link RagSearchHandler} 间传递。
 *
 * <p>包含原始查询、多路召回结果、最终融合结果、质量评分等。
 * Handler 可通过 {@link #setAttribute(String, Object)} 传递自定义数据。
 */
public class RagSearchContext {

    /** 原始用户查询 */
    private final String originalQuery;

    /** 查询变体列表（含 Multi-Query 扩展结果） */
    private final List<String> queryVariants;

    /** 多路召回结果：pathName → items */
    private final Map<String, RetrievalPathResult> pathResults;

    /** 最终融合结果 */
    private final List<RankedItem> fusedResults;

    /** 是否已终止（后续 Handler 可检查并跳过） */
    private boolean terminated;

    /** 质量分数（0.0~1.0） */
    private double qualityScore;

    /** 质量阈值 */
    private double qualityThreshold;

    /** 额外属性存储 */
    private final Map<String, Object> attributes;

    /** 起始时间 */
    private final long startTime;

    public RagSearchContext(String originalQuery) {
        this.originalQuery = originalQuery;
        this.queryVariants = new ArrayList<>();
        this.queryVariants.add(originalQuery);
        this.pathResults = new LinkedHashMap<>();
        this.fusedResults = new ArrayList<>();
        this.terminated = false;
        this.qualityScore = 0.0;
        this.qualityThreshold = 0.30;
        this.attributes = new ConcurrentHashMap<>();
        this.startTime = System.currentTimeMillis();
    }

    // ==================== Getters ====================

    public String getOriginalQuery() { return originalQuery; }
    public List<String> getQueryVariants() { return queryVariants; }
    public Map<String, RetrievalPathResult> getPathResults() { return pathResults; }
    public List<RankedItem> getFusedResults() { return fusedResults; }
    public boolean isTerminated() { return terminated; }
    public double getQualityScore() { return qualityScore; }
    public double getQualityThreshold() { return qualityThreshold; }
    public long getElapsedMs() { return System.currentTimeMillis() - startTime; }
    public long getStartTime() { return startTime; }

    // ==================== Setters ====================

    public void setTerminated(boolean terminated) { this.terminated = terminated; }
    public void setQualityScore(double qualityScore) { this.qualityScore = qualityScore; }
    public void setQualityThreshold(double qualityThreshold) { this.qualityThreshold = qualityThreshold; }

    public void addQueryVariant(String variant) {
        if (!queryVariants.contains(variant)) {
            queryVariants.add(variant);
        }
    }

    public void addQueryVariants(List<String> variants) {
        for (String v : variants) {
            addQueryVariant(v);
        }
    }

    public void addPathResult(String pathName, List<String> items) {
        pathResults.put(pathName, new RetrievalPathResult(pathName, items));
    }

    public void setFusedResults(List<RankedItem> results) {
        fusedResults.clear();
        fusedResults.addAll(results);
    }

    // ==================== 属性操作 ====================

    public Object getAttribute(String key) { return attributes.get(key); }
    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Map<String, Object> getAttributes() { return attributes; }

    // ==================== 内部类 ====================

    /** 单条检索路径的结果 */
    public static class RetrievalPathResult {
        private final String pathName;
        private final List<String> items;

        public RetrievalPathResult(String pathName, List<String> items) {
            this.pathName = pathName;
            this.items = items != null ? items : List.of();
        }

        public String getPathName() { return pathName; }
        public List<String> getItems() { return items; }
        public boolean isEmpty() { return items.isEmpty(); }
    }

    /** 带 RRF 分数的检索结果项 */
    public static class RankedItem {
        private final String content;
        private double rrfScore;

        public RankedItem(String content, double rrfScore) {
            this.content = content;
            this.rrfScore = rrfScore;
        }

        public String getContent() { return content; }
        public double getRrfScore() { return rrfScore; }
        public void addScore(double score) { this.rrfScore += score; }
    }
}
