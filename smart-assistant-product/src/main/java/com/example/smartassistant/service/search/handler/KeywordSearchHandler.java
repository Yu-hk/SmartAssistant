/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.pipeline.RagSearchHandler;
import com.example.smartassistant.spi.ProductBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * H02: 关键词模糊搜索 Handler。
 *
 * <p>通过 ProductBackend.searchProduct() 做关键词搜索。
 * 对所有 query variant 执行。
 */
@Component
public class KeywordSearchHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearchHandler.class);

    private final ProductBackend productBackend;

    public KeywordSearchHandler(ProductBackend productBackend) {
        this.productBackend = productBackend;
    }

    @Override
    public void handle(RagSearchContext context) {
        List<String> allResults = new ArrayList<>();

        for (String variant : context.getQueryVariants()) {
            try {
                String result = productBackend.searchProduct(variant);
                if (result != null && !result.contains("未找到")) {
                    for (String line : result.split("\n")) {
                        if (line.startsWith("·")) {
                            allResults.add(line.substring(1).trim());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[RagHandler] KeywordSearch 失败 (variant={}): {}", variant, e.getMessage());
            }
        }

        context.addPathResult("关键词搜索", allResults);
        log.info("[RagHandler] KeywordSearch: {} results for {} variants", allResults.size(), context.getQueryVariants().size());
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
