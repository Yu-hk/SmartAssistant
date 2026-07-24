/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

/**
 * 文档块角色——标识 {@link KnowledgeDocument} 在 Parent-Child 双粒度分块中的身份。
 * <ul>
 *   <li>{@code PARENT}：父块（整接口 / 大段上下文），用于 LLM 阅读；入库时<b>不嵌入</b>，不参与向量检索；</li>
 *   <li>{@code CHILD}：子块（小节 / 细粒度），用于向量检索，携带 {@code parentDocId} 关联父块；</li>
 *   <li>{@code STANDALONE}：独立块（普通分块或结构化整篇），既检索又阅读，正常嵌入。</li>
 * </ul>
 */
public enum ChunkRole {
    PARENT,
    CHILD,
    STANDALONE
}
