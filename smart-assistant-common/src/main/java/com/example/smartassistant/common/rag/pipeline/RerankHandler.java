/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 重排序 Handler。
 *
 * <p>参考 Snail AI 的 {@code RerankHandler} 设计。
 * 对 RRF 融合后的结果做二次精排，使用 cross-encoder 或嵌入模型重新评分。
 *
 * <p>在 Pipeline 中 Order=110，在 {@link RrfFusionHandler} (Order=100) 之后执行。
 *
 * <p>评分器注入方式：{@link BiFunction}{@code <String, String, Double>}，
 * 接收 (query, documentText) 返回相关性分数（0.0~1.0）。
 * 默认使用 {@code RerankHandler.identity()} 恒等评分（不改变排序）。
 */
public class RerankHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(RerankHandler.class);

    /** 重排序评分器：(query, docText) → relevanceScore (0.0~1.0) */
    private final BiFunction<String, String, Double> scorer;

    /** 是否启用 */
    private final boolean enabled;

    /** 保留的 Top-K 结果数（<=0 表示保留全部） */
    private final int topK;

    public RerankHandler(BiFunction<String, String, Double> scorer, boolean enabled, int topK) {
        this.scorer = scorer != null ? scorer : identity();
        this.enabled = enabled;
        this.topK = topK > 0 ? topK : 5;
    }

    public RerankHandler(BiFunction<String, String, Double> scorer) {
        this(scorer, true, 5);
    }

    @Override
    public void handle(RagSearchContext context) {
        if (!enabled) {
            log.debug("[RerankHandler] 未启用，跳过");
            return;
        }

        List<RagSearchContext.RankedItem> fused = context.getFusedResults();
        if (fused == null || fused.isEmpty()) {
            return;
        }

        String query = context.getOriginalQuery();
        long start = System.currentTimeMillis();

        // 对所有结果重新评分
        List<ScoredItem> reScored = new ArrayList<>();
        for (RagSearchContext.RankedItem item : fused) {
            try {
                double score = scorer.apply(query, item.getContent());
                reScored.add(new ScoredItem(item, score));
            } catch (Exception e) {
                log.warn("[RerankHandler] 评分失败: {}", e.getMessage());
                reScored.add(new ScoredItem(item, item.getRrfScore()));
            }
        }

        // 按新分数降序排列
        reScored.sort(Comparator.<ScoredItem>comparingDouble(s -> s.newScore).reversed());

        // 重建 RankedItem 列表（保留原始内容，更新分数）
        int limit = Math.min(topK, reScored.size());
        List<RagSearchContext.RankedItem> reranked = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            ScoredItem si = reScored.get(i);
            // 创建新的 RankedItem，保留 content，使用新的精排分数
            RagSearchContext.RankedItem ri = new RagSearchContext.RankedItem(
                    si.item.getContent(), si.newScore);
            reranked.add(ri);
        }

        context.setFusedResults(reranked);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[RerankHandler] 重排完成: {}→{} items, 耗时={}ms",
                fused.size(), reranked.size(), elapsed);
    }

    /**
     * 恒等评分器——返回原始 RRF 分数，不改变排序。
     */
    public static BiFunction<String, String, Double> identity() {
        return (query, docText) -> 0.5;
    }

    @Override
    public int getOrder() {
        return 110; // 在 RrfFusionHandler (Order=100) 之后执行
    }

    /** 带新分数的结果项 */
    private record ScoredItem(RagSearchContext.RankedItem item, double newScore) {}
}
