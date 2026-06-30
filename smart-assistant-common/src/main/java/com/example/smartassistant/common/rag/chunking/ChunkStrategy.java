/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.chunking;

import java.util.List;

/**
 * 文档分块策略接口——将解析后的文档元素切分为适合 Embedding 的文本块。
 * <p>
 * 参考 RAG 文章：chunk 切得太碎，信息被拆散；chunk 太大，相关信息被淹没。
 * 不同场景需要不同的分块策略。
 * </p>
 */
@FunctionalInterface
public interface ChunkStrategy {

    /**
     * 对文本进行分块。
     *
     * @param text      待分块的文本
     * @param maxTokens 每块最大 token 数
     * @param overlap   块间重叠 token 数
     * @return 分块后的文本列表
     */
    List<Chunk> chunk(String text, int maxTokens, int overlap);

    /**
     * 默认分块参数：BGE-small-zh 支持 512 token，与模型原生能力对齐
     */
    static int defaultMaxTokens() {
        return 512;
    }

    static int defaultOverlap() {
        return 50;
    }
}
