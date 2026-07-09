/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 重排序 Top-K 自适应解析器（文章 Q⑦「按意图类型自适应 K」落地）。
 *
 * <p>RAG 检索的「最终注入上下文条数」不应一成不变：
 * <ul>
 *   <li><b>开放式 / 比较型 / 攻略型</b>查询（"对比两款""推荐排行""怎么选""区别是什么"）需要更多候选，
 *       以覆盖多维度信息 → 取 {@link #maxK}；</li>
 *   <li><b>事实型 / 查数型</b>查询（"多少钱""哪款""电话多少""营业时间"）答案收敛，条数过多反而引入噪声 → 取 {@link #minK}；</li>
 *   <li>其余 → 取 {@link #defaultK}。</li>
 * </ul>
 *
 * <p>实现为零 LLM 的确定性关键词映射，可单测、可观测、不阻断主链路。
 */
public class AdaptiveRerankTopK {

    /** 最小 K（事实型查询） */
    private final int minK;
    /** 默认 K */
    private final int defaultK;
    /** 最大 K（开放式查询） */
    private final int maxK;

    /** 开放式 / 比较型 / 攻略型关键词 → 取 maxK */
    private static final List<String> OPEN_KEYWORDS = List.of(
            "对比", "比较", "推荐", "排行", "攻略", "方案", "哪个更好", "区别",
            "怎么", "如何", "为什么", "怎样", "有哪些", "哪个好", "选哪个", "搭配");

    /** 事实型 / 查数型关键词 → 取 minK */
    private static final List<String> FACT_KEYWORDS = List.of(
            "多少", "几多", "价格", "价钱", "多少钱", "哪款", "哪一对", "是什么", "哪一年",
            "几月", "几号", "电话", "地址", "营业", "几点", "尺寸", "规格", "参数", "上市时间");

    public AdaptiveRerankTopK(int minK, int defaultK, int maxK) {
        if (minK <= 0 || defaultK <= 0 || maxK <= 0) {
            throw new IllegalArgumentException("Top-K 必须为正数");
        }
        this.minK = Math.min(minK, maxK);
        this.defaultK = Math.max(this.minK, Math.min(defaultK, maxK));
        this.maxK = Math.max(maxK, this.minK);
    }

    public AdaptiveRerankTopK() {
        this(3, 5, 8);
    }

    /**
     * 根据查询解析本应使用的最终 Top-K。
     *
     * @param query 原始用户查询（可空，空串按默认处理）
     * @return 自适应 K 值（落在 [minK, maxK] 区间）
     */
    public int resolve(String query) {
        if (query == null || query.isBlank()) {
            return defaultK;
        }
        String q = query.toLowerCase(Locale.ROOT);
        if (containsAny(q, OPEN_KEYWORDS)) {
            return maxK;
        }
        if (containsAny(q, FACT_KEYWORDS)) {
            return minK;
        }
        return defaultK;
    }

    public int getMinK() { return minK; }
    public int getDefaultK() { return defaultK; }
    public int getMaxK() { return maxK; }

    /**
     * 转换为 {@link Function}{@code <String, Integer>}，便于直接注入 {@link RerankHandler}。
     */
    public Function<String, Integer> asResolver() {
        return this::resolve;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
