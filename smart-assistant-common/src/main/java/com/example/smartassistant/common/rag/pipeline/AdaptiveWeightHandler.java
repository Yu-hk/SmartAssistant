/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 动态自适应权重 Handler。
 *
 * <p>参考腾讯面试题 #1 的设计，根据 query 特征动态调整稀疏/稠密检索权重：
 * <ul>
 *   <li>短 query、含专业术语 → 降低稠密权重，提升稀疏检索优先级（适用于精确匹配场景）</li>
 *   <li>长问句、口语化、模糊意图 → 提升稠密权重，优先语义匹配（适用于语义检索场景）</li>
 * </ul>
 *
 * <p>Order=3，在 QueryRewriteHandler (Order=2) 之后执行。
 * 将权重参数存入 context attributes，供 RrfFusionHandler 读取。
 */
public class AdaptiveWeightHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveWeightHandler.class);

    /** Context attribute key：稀疏检索权重（BM25/关键词） */
    public static final String ATTR_SPARSE_WEIGHT = "adaptive.sparseWeight";
    /** Context attribute key：稠密检索权重（向量/语义） */
    public static final String ATTR_DENSE_WEIGHT = "adaptive.denseWeight";

    @Override
    public void handle(RagSearchContext context) {
        String query = context.getOriginalQuery();
        if (query == null || query.isBlank()) return;

        double denseWeight = computeDenseWeight(query);
        double sparseWeight = 1.0 - denseWeight;

        context.setAttribute(ATTR_DENSE_WEIGHT, denseWeight);
        context.setAttribute(ATTR_SPARSE_WEIGHT, sparseWeight);

        log.debug("[AdaptiveWeight] query='{}' (len={}, ratio={}), dense={}, sparse={}",
                truncate(query, 50), query.length(),
                String.format("%.2f", computeTermRatio(query)),
                String.format("%.2f", denseWeight), String.format("%.2f", sparseWeight));
    }

    /**
     * 计算稠密检索权重（0.3~0.8）。
     *
     * <p>策略：
     * <ul>
     *   <li>短查询（≤5字符）→ 偏向稠密（0.7），因为短文本难以提取有效关键词</li>
     *   <li>长查询（≥20字符）→ 偏向稠密（0.7~0.8），复杂语义需要向量理解</li>
     *   <li>专业术语多（英文/数字占比高）→ 偏向稀疏（0.3~0.5），精确匹配更佳</li>
     *   <li>口语化（语气词/疑问词占比高）→ 偏向稠密（0.6~0.8）</li>
     * </ul>
     */
    private double computeDenseWeight(String query) {
        double baseWeight = 0.6; // 默认 0.6 稠密
        double termRatio = computeTermRatio(query);

        // 长度因子
        int len = query.length();
        if (len <= 5) baseWeight = 0.7;        // 短查询偏向语义
        else if (len >= 20) baseWeight = 0.7;  // 长查询偏向语义

        // 专业术语（英文+数字）占比高 → 降稠密
        if (termRatio > 0.4) {
            baseWeight -= 0.2; // 降到 0.4~0.5
        } else if (termRatio < 0.1) {
            baseWeight += 0.1; // 纯中文 → 升到 0.7~0.8
        }

        // 口语化检测 → 升稠密
        String colloqPattern = "(?s).*[吗|呢|吧|啊|呀|嘛|哈|嗯|哦|啥|怎么|为什么|如何|怎样].*";
        if (query.matches(colloqPattern)) {
            baseWeight += 0.1;
        }

        // 限定到 0.3~0.8
        return Math.max(0.3, Math.min(0.8, baseWeight));
    }

    /**
     * 计算查询中英文+数字的字符占比。
     * 高比例 → 专业术语多 → 偏稀疏检索。
     */
    private double computeTermRatio(String query) {
        if (query == null || query.isBlank()) return 0;
        long termChars = query.chars().filter(c ->
                (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')).count();
        return (double) termChars / query.length();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    @Override
    public int getOrder() {
        return 3; // 在 QueryRewriteHandler (Order=2) 之后
    }
}
