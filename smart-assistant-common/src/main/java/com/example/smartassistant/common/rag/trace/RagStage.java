/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.trace;

/**
 * RAG 全阶段 trace 的阶段枚举。
 * <p>
 * 用于把一次请求的质量问题定位到具体阶段（对标文章《RAG 系统从 Demo 到生产》
 * 中的"质量问题定位到阶段"能力）：
 * <ul>
 *   <li>{@link #RETRIEVAL} — 检索 + 重排阶段（向量/BM25/图谱/融合）</li>
 *   <li>{@link #GENERATION} — LLM 生成阶段</li>
 *   <li>{@link #REJECTION} — 无证据拒答（在生成前短路，不调用 LLM）</li>
 * </ul>
 */
public enum RagStage {

    /** 检索 + 重排阶段 */
    RETRIEVAL,

    /** LLM 生成阶段 */
    GENERATION,

    /** 无证据拒答（生成前短路） */
    REJECTION
}
