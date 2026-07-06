/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import org.springframework.core.Ordered;

/**
 * RAG 检索管线处理器 SPI。
 *
 * <p>参考 Snail AI 的 {@code RagSearchHandler} 设计，Pipeline 模式使检索流程
 * 高度可扩展，每个阶段可独立替换。
 *
 * <p>通过 {@link Ordered#getOrder()} 控制执行顺序：
 * <ol>
 *   <li>查询重写（Order=10）</li>
 *   <li>向量检索（Order=20）</li>
 *   <li>BM25 全文检索（Order=30）</li>
 *   <li>混合融合（Order=40）</li>
 *   <li>重排序（Order=50）</li>
 * </ol>
 *
 * @see RagSearchPipeline
 * @see RagSearchContext
 */
public interface RagSearchHandler extends Ordered {

    /**
     * 执行检索或后处理。
     *
     * @param context 检索上下文，Handler 通过修改 context 传递结果
     */
    void handle(RagSearchContext context);
}
