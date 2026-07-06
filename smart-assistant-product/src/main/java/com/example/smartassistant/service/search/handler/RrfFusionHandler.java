/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.pipeline.RagSearchHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * H99: RRF 融合 + 质量评估 Handler（管线终点）。
 *
 * <p>对所有检索路径的结果做 Reciprocal Rank Fusion 融合，
 * 评估 Top-1 RRF 分数作为质量指标，低于阈值时标记低质量。
 * 输出格式化后的检索结果字符串。
 */
@Component
public class RrfFusionHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(RrfFusionHandler.class);

    /** RRF 融合常数 */
    private static final int RRF_K = 60;

    /** Top-K 结果数 */
    private static final int TOP_K = 5;

    @Value("${product.rag.quality-threshold:0.30}")
    private double qualityThreshold;

    @Override
    public void handle(RagSearchContext context) {
        context.setQualityThreshold(qualityThreshold);

        // 收集所有 path 的结果进行 RRF 融合
        List<String> allItems = new ArrayList<>();
        Map<String, RagSearchContext.RetrievalPathResult> pathResults = context.getPathResults();

        for (RagSearchContext.RetrievalPathResult path : pathResults.values()) {
            allItems.addAll(path.getItems());
        }

        if (allItems.isEmpty()) {
            context.setQualityScore(0.0);
            context.setFusedResults(List.of());
            log.warn("[RagHandler] RRF: 全部路径未召回, query={}", context.getOriginalQuery());
            return;
        }

        // RRF 融合
        Map<String, RagSearchContext.RankedItem> fusedMap = new LinkedHashMap<>();
        for (RagSearchContext.RetrievalPathResult path : pathResults.values()) {
            List<String> items = path.getItems();
            for (int i = 0; i < items.size(); i++) {
                int rank = i + 1;
                double rrfScore = 1.0 / (RRF_K + rank);
                String content = items.get(i);
                fusedMap.computeIfAbsent(content, k -> new RagSearchContext.RankedItem(content, 0.0))
                        .addScore(rrfScore);
            }
        }

        // 排序取 Top-K
        List<RagSearchContext.RankedItem> fused = fusedMap.values().stream()
                .sorted((a, b) -> Double.compare(b.getRrfScore(), a.getRrfScore()))
                .limit(TOP_K)
                .collect(Collectors.toList());

        context.setFusedResults(fused);

        // 质量评估
        double rrfMax = pathResults.size() / (double)(RRF_K + 1);
        double topRrf = fused.get(0).getRrfScore();
        double qualityScore = Math.min(1.0, topRrf / rrfMax);
        context.setQualityScore(qualityScore);

        int activePaths = (int) pathResults.values().stream().filter(p -> !p.isEmpty()).count();
        log.info("[RagHandler] RRF 融合完成: activePaths={}, fused={}, qualityScore={}",
                activePaths, fused.size(), String.format("%.4f", qualityScore));
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
