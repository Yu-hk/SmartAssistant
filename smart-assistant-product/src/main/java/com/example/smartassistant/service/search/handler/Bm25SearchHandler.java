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
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.example.smartassistant.spi.ProductBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

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
    private final ChineseTokenizer tokenizer;

    /** Immutable scorer/documents pair; refresh atomically when catalog intake changes. */
    private volatile ProductIndex index;
    private volatile long nextRefreshNanos;

    @Value("${product.rag.bm25-refresh-ms:60000}")
    private long refreshMillis = 60000;

    public Bm25SearchHandler(ProductBackend productBackend, ChineseTokenizer tokenizer) {
        this.productBackend = productBackend;
        this.tokenizer = tokenizer;
    }

    @Override
    public void handle(RagSearchContext context) {
        ProductIndex current = currentIndex();
        if (current == null || current.docs().isEmpty()) {
            context.addPathResult("BM25", List.of());
            return;
        }

        Set<String> allResults = new LinkedHashSet<>();

        for (String variant : context.getQueryVariants()) {
            try {
                var ranked = current.scorer().rerank(current.docs(), variant, TOP_K);
                for (var entry : ranked) {
                    KnowledgeDocument doc = entry.getKey();
                    String code = doc.getId();
                    try {
                        String info = productBackend.queryProductInfo(code);
                        if (info != null && !info.contains("PRODUCT_NOT_FOUND")
                                && !info.contains("TOOL_EXECUTION_ERROR")
                                && !info.contains("TOOL_INVALID_ARGUMENT")) {
                            allResults.add(info);
                        }
                    } catch (Exception e) {
                        log.debug("[RagHandler] BM25 单个查询失败: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[RagHandler] BM25 失败 (variant={}): {}", variant, e.getMessage());
            }
        }

        context.addPathResult("BM25", List.copyOf(allResults));
        log.info("[RagHandler] BM25: {} results for {} variants", allResults.size(), context.getQueryVariants().size());
    }

    private synchronized ProductIndex currentIndex() {
        long now = System.nanoTime();
        if (now - nextRefreshNanos < 0) return index;
        // Retry a failed catalog read soon; a successful snapshot refreshes at
        // the configured interval without mutating an in-flight scorer.
        nextRefreshNanos = now + TimeUnit.SECONDS.toNanos(10);
        List<KnowledgeDocument> docs = new ArrayList<>();
        try {
            for (ProductBackend.ProductSearchDocument product : productBackend.listProductSearchDocuments()) {
                if (product.code() == null || product.code().isBlank()
                        || product.name() == null || product.name().isBlank()) continue;
                // Index stable catalog text; fetch live price and stock by code only after a hit.
                docs.add(new KnowledgeDocument(product.code(), product.name(),
                        product.code() + " " + product.name() + " " + Objects.toString(product.spec(), ""),
                        "product", "", -1L, -1L));
            }
        } catch (Exception e) {
            log.warn("[RagHandler] BM25 商品目录读取失败: {}", e.getMessage());
            return index;
        }

        Bm25Scorer scorer = new Bm25Scorer(tokenizer);
        List<KnowledgeDocument> snapshot = List.copyOf(docs);
        scorer.initialize(snapshot);
        index = new ProductIndex(snapshot, scorer);
        nextRefreshNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                refreshMillis > 0 ? refreshMillis : 60000);
        log.info("[RagHandler] BM25 索引重建完成: {} 个产品", snapshot.size());
        return index;
    }

    private record ProductIndex(List<KnowledgeDocument> docs, Bm25Scorer scorer) { }

    @Override
    public int getOrder() {
        return 30;
    }
}
