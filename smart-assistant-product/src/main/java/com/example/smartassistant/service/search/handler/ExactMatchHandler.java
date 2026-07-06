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
 * H01: 精确匹配检索 Handler。
 *
 * <p>通过 ProductBackend.queryProductInfo() 按商品编码精确查询，
 * 同时尝试 ProductBackend.searchProduct() 做关键词模糊匹配。
 * 对所有 query variant 执行同一逻辑。
 */
@Component
public class ExactMatchHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(ExactMatchHandler.class);

    private final ProductBackend productBackend;

    public ExactMatchHandler(ProductBackend productBackend) {
        this.productBackend = productBackend;
    }

    @Override
    public void handle(RagSearchContext context) {
        List<String> allResults = new ArrayList<>();

        for (String variant : context.getQueryVariants()) {
            try {
                // 尝试直接作为 productCode 查询
                String info = productBackend.queryProductInfo(variant.trim().toUpperCase());
                if (info != null && !info.contains("PRODUCT_NOT_FOUND")) {
                    allResults.add(info);
                }

                // 也尝试搜索
                String searchResult = productBackend.searchProduct(variant);
                if (searchResult != null && !searchResult.contains("未找到")) {
                    for (String line : searchResult.split("\n")) {
                        if (line.startsWith("·")) {
                            String name = line.replace("·", "").trim().split("—")[0].trim();
                            String detail = productBackend.queryProductInfo(name);
                            if (detail != null && !detail.contains("PRODUCT_NOT_FOUND")) {
                                allResults.add(detail);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[RagHandler] ExactMatch 失败 (variant={}): {}", variant, e.getMessage());
            }
        }

        context.addPathResult("精确匹配", allResults);
        log.info("[RagHandler] ExactMatch: {} results for {} variants", allResults.size(), context.getQueryVariants().size());
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
