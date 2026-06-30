/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 安全 Reranker 包装器——将任何异常从 Reranker 调用中隔离出来，
 * 确保单个评分失败不会影响整个检索流程。
 * <p>
 * 异常时降级为恒等映射（保留原始排序），并记录 warn 日志。
 * </p>
 */
public class SafeReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(SafeReranker.class);

    private final Reranker delegate;
    private final String name;

    public SafeReranker(Reranker delegate) {
        this.delegate = delegate;
        this.name = delegate.getClass().getSimpleName();
    }

    @Override
    public List<KnowledgeHit> rerank(List<KnowledgeHit> hits, String query, int topK) {
        if (hits == null || hits.isEmpty()) return hits;

        try {
            long start = System.currentTimeMillis();
            List<KnowledgeHit> result = delegate.rerank(hits, query, topK);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[SafeReranker:{}] 重排序完成: {}→{} 条, 耗时={}ms",
                    name, hits.size(), result != null ? result.size() : 0, elapsed);
            return result != null ? result : hits.subList(0, Math.min(topK, hits.size()));
        } catch (Exception e) {
            log.warn("[SafeReranker:{}] 重排序异常，降级为恒等映射: {}", name, e.getMessage());
            return hits.size() <= topK ? hits : hits.subList(0, topK);
        }
    }
}
