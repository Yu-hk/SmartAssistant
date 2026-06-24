/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import java.util.List;

/**
 * 知识库接口——支持向量检索的知识库抽象。
 * <p>
 * 支持增删文档、BGE 向量嵌入、余弦相似度搜索、两级检索（粗筛+精排）。
 * </p>
 */
public interface KnowledgeBase {

    /** 知识库名称 */
    String getName();

    /** 添加文档（自动计算 embedding） */
    void addDocument(KnowledgeDocument doc);

    /** 批量添加文档 */
    default void addDocuments(List<KnowledgeDocument> docs) {
        docs.forEach(this::addDocument);
    }

    /** 按 ID 删除文档 */
    void removeDocument(String id);

    /**
     * 检索最相关的文档。
     *
     * @param query  检索查询（自然语言）
     * @param topK   返回条数
     * @return 按相关度降序排列的结果
     */
    List<KnowledgeHit> search(String query, int topK);

    /** 获取文档总数 */
    int size();

    /** 重新计算所有文档的 embedding（知识库变更后调用） */
    void reindex();
}
