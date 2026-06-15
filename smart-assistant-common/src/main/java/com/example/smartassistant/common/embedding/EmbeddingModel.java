/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.embedding;

import com.example.smartassistant.common.document.Document;

import java.util.List;

/**
 * 向量化模型接口，替代 Spring AI 1.x 的 {@code org.springframework.ai.embedding.EmbeddingModel}。
 * <p>支持文本/文档的向量化生成，以及对批量请求的处理。</p>
 */
public interface EmbeddingModel {

    /**
     * 对多个文本批量生成向量。
     *
     * @param texts 待向量化的文本列表
     * @return 向量列表（与输入顺序一致）
     */
    List<float[]> embed(List<String> texts);

    /**
     * 对单个文本生成向量。
     *
     * @param text 待向量化的文本
     * @return 向量
     */
    float[] embed(String text);

    /**
     * 对文档的文本内容生成向量。
     *
     * @param document 文档
     * @return 向量
     */
    float[] embed(Document document);

    /**
     * 完整的向量化请求处理（支持自定义选项）。
     *
     * @param request 向量化请求
     * @return 向量化响应
     */
    EmbeddingResponse call(EmbeddingRequest request);

    /**
     * 获取向量维度。
     *
     * @return 向量维度
     */
    int dimensions();
}
