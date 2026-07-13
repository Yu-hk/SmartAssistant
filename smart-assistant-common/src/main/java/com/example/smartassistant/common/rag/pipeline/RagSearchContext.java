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

    /** 管线执行过程中产生的结构化错误清单（异常分级用，见 {@link PipelineError}） */
    private final List<PipelineError> errors = new ArrayList<>();

    /** 是否发生降级（任一 Handler 抛出异常即标记，供上游观测） */
    private boolean degraded;

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

    // ==================== 异常分级 ====================

    /**
     * 记录一个 Handler 产生的结构化错误（异常分级核心载体）。
     * <p>由 {@link RagSearchPipeline} 在捕获 Handler 异常时调用，
     * 将异常类型/错误码/消息归一化为 {@link PipelineError}，
     * 供 {@code MetricsCollectorHandler} 与上游观测消费，而非仅打印日志。</p>
     *
     * @param handlerName 出错 Handler 的简单类名
     * @param errorCode   机器可读错误码（如 {@code RAG_EMBEDDING_UNAVAILABLE}；非标准异常为 {@code UNCLASSIFIED}）
     * @param message     错误描述
     */
    public void addError(String handlerName, String errorCode, String message) {
        this.errors.add(new PipelineError(handlerName, errorCode, message, System.currentTimeMillis()));
        this.degraded = true;
    }

    /** 结构化错误清单（不可变视图） */
    public List<PipelineError> getErrors() { return List.copyOf(errors); }

    /** 是否发生降级（任一 Handler 抛异常） */
    public boolean isDegraded() { return degraded; }

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

    /**
     * 管线执行过程中的结构化错误记录（异常分级载体）。
     *
     * <p>替代原先「{@code log.warn} 后静默吞掉」的扁平降级，
     * 让每个 Handler 的失败携带机器可读的 {@code errorCode} 与发生时间戳，
     * 便于 {@code MetricsCollectorHandler} 暴露 {@code rag.handler.failures} 指标、
     * 以及上游据此判断是否需要重试 / 降级 / 提示用户。</p>
     *
     * @param handlerName 出错 Handler 的简单类名
     * @param errorCode   机器可读错误码（标准码或 {@code UNCLASSIFIED}）
     * @param message     错误描述
     * @param timestamp   发生时间戳（毫秒）
     */
    public record PipelineError(String handlerName, String errorCode, String message, long timestamp) {
    }
}
