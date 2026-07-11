/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.Bm25Scorer;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.pipeline.RagSearchHandler;
import com.example.smartassistant.spi.ProductBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * H03: BM25 语义评分检索 Handler。
 *
 * <p>将所有产品文本构建为 {@link KnowledgeDocument} 集合，
 * 使用 {@link Bm25Scorer#rerank(List, String, int)} 排序，取 Top-K。
 */
@Component
public class Bm25SearchHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(Bm25SearchHandler.class);

    private static final int TOP_K = 5;

    private final ProductBackend productBackend;
    private final Bm25Scorer bm25Scorer;

    /** 产品文档缓存 */
    private List<KnowledgeDocument> productDocs;
    private boolean initialized = false;

    public Bm25SearchHandler(ProductBackend productBackend, Bm25Scorer bm25Scorer) {
        this.productBackend = productBackend;
        this.bm25Scorer = bm25Scorer;
    }

    @Override
    public void handle(RagSearchContext context) {
        if (!initialized || productDocs == null || productDocs.isEmpty()) {
            rebuildCache();
        }
        if (productDocs == null || productDocs.isEmpty()) {
            context.addPathResult("BM25", List.of());
            return;
        }

        List<String> allResults = new ArrayList<>();

        for (String variant : context.getQueryVariants()) {
            try {
                var ranked = bm25Scorer.rerank(productDocs, variant, TOP_K);
                for (var entry : ranked) {
                    KnowledgeDocument doc = entry.getKey();
                    String name = doc.getId();
                    try {
                        String info = productBackend.queryProductInfo(name);
                        if (info != null && !info.contains("PRODUCT_NOT_FOUND")) {
                            allResults.add(info);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                log.warn("[RagHandler] BM25 失败 (variant={}): {}", variant, e.getMessage());
            }
        }

        context.addPathResult("BM25", allResults);
        log.info("[RagHandler] BM25: {} results for {} variants", allResults.size(), context.getQueryVariants().size());
    }

    private synchronized void rebuildCache() {
        if (initialized && productDocs != null && !productDocs.isEmpty()) return;

        List<KnowledgeDocument> docs = new ArrayList<>();
        String allProducts = productBackend.searchProduct("");

        if (allProducts != null && !allProducts.contains("未找到")) {
            for (String line : allProducts.split("\n")) {
                if (line.startsWith("·")) {
                    String name = line.replace("·", "").trim().split("—")[0].trim();
                    try {
                        String info = productBackend.queryProductInfo(name);
                        if (info != null) {
                            // 使用 KnowledgeDocument 构建，以 name 作为 id，info 同时作为 title 和 content
                            docs.add(new KnowledgeDocument(
                                    name,                   // id
                                    name,                   // title
                                    info,                   // content
                                    "product",              // category
                                    "",                     // keywords
                                    -1L,                    // effectiveAt (永久)
                                    -1L                     // expireAt (永不过期)
                            ));
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        if (!docs.isEmpty()) {
            productDocs = docs;
            bm25Scorer.initialize(productDocs);
            log.info("[RagHandler] BM25 索引重建完成: {} 个产品", productDocs.size());
        } else {
            productDocs = List.of();
        }
        initialized = true;
    }

    @Override
    public int getOrder() {
        return 30;
    }
}
