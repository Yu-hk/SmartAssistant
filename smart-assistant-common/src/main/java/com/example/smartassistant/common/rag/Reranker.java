/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import java.util.List;

/**
 * 重排序器接口——对检索结果进行第二次精排。
 * <p>
 * 参照 RAG 文章的两阶段架构：向量检索（粗筛，Bi-Encoder）→ 重排（精排，Cross-Encoder）。
 * 默认实现为恒等映射（不对排序做任何修改）。
 * </p>
 */
@FunctionalInterface
public interface Reranker {

    /**
     * 对候选文档重新排序。
     *
     * @param hits   候选结果
     * @param query  用户查询
     * @param topK   保留条数
     * @return 重新排序后的结果
     */
    List<KnowledgeHit> rerank(List<KnowledgeHit> hits, String query, int topK);

    /**
     * 恒等重排序器——不改变原始排序。
     */
    static Reranker identity() {
        return (hits, query, topK) ->
                hits.size() <= topK ? hits : hits.subList(0, topK);
    }
}
