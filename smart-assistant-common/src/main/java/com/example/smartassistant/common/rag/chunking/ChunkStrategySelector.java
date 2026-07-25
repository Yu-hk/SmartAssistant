/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.chunking;

import com.example.smartassistant.common.rag.document.ParsedDocument;

/**
 * 分块策略选择器——按文档特征（类型 / 结构 / 长度）为单个 {@link ParsedDocument} 选择 {@link ChunkStrategy}。
 * <p>
 * 相比「对整个文档列表用单一策略」，按文档维度路由能实现：
 * 图片/OCR 流水走 BGE 语义切分，有结构的 txt/pdf/word 走规则切分，短文本不切。
 * </p>
 */
@FunctionalInterface
public interface ChunkStrategySelector {

    /**
     * 为给定解析文档选择分块策略。
     *
     * @param doc 解析后的文档单元（含 contentType 等路由信号）
     * @return 该文档使用的分块策略（禁止返回 null，调用方应提供默认策略）
     */
    ChunkStrategy select(ParsedDocument doc);
}
