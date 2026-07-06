/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.pipeline.RagSearchHandler;
import com.example.smartassistant.service.graph.ProductGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * H05: Graph 图检索 Handler。
 *
 * <p>从用户查询中提取商品编码，查询关系图谱获取同类/配件/替代品推荐。
 */
@Component
public class GraphSearchHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(GraphSearchHandler.class);

    private final ProductGraphService productGraphService;

    public GraphSearchHandler(ProductGraphService productGraphService) {
        this.productGraphService = productGraphService;
    }

    @Override
    public void handle(RagSearchContext context) {
        List<String> results = new ArrayList<>();

        try {
            String matchedCode = productGraphService.matchProduct(context.getOriginalQuery());
            if (matchedCode != null) {
                String name = productGraphService.getProductName(matchedCode);
                var recommendations = productGraphService.queryRecommendations(matchedCode, 3);
                if (!recommendations.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("【关联推荐】基于 ").append(name != null ? name : matchedCode).append("：\n");
                    int rank = 1;
                    for (var rec : recommendations) {
                        sb.append("  ").append(rank).append(". ")
                                .append(rec.getProductName())
                                .append(" (").append(rec.getRelationType().name()).append(")")
                                .append("\n");
                        rank++;
                    }
                    results.add(sb.toString().trim());
                }
            }
        } catch (Exception e) {
            log.warn("[RagHandler] GraphSearch 失败: {}", e.getMessage());
        }

        context.addPathResult("图谱检索", results);
        log.info("[RagHandler] GraphSearch: {} results", results.size());
    }

    @Override
    public int getOrder() {
        return 50;
    }
}
