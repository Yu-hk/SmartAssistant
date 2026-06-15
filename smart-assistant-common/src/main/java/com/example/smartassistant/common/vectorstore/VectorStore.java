/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.vectorstore;

import com.example.smartassistant.common.document.Document;

import java.util.List;

/**
 * 向量存储接口，替代 Spring AI 1.x 的 {@code org.springframework.ai.vectorstore.VectorStore}。
 * <p>支持文档的添加以及基于语义相似度的检索。</p>
 */
public interface VectorStore {

    /**
     * 添加文档到向量存储。
     *
     * @param documents 待添加的文档列表
     */
    void add(List<Document> documents);

    /**
     * 基于语义相似度搜索文档。
     *
     * @param request 搜索请求（包含查询文本、Top-K、阈值等）
     * @return 按相似度降序排列的文档列表
     */
    List<Document> similaritySearch(SearchRequest request);
}
