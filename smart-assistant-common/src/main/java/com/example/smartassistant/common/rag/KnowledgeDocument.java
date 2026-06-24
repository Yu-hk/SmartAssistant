/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import lombok.Getter;

/**
 * 知识文档——RAG 检索的基本单元。
 * <p>
 * 包含文档内容、元信息、分类标签和时效性字段。
 * 对应 RAG 治理文章中的"召回单元"概念。
 * </p>
 */
@Getter
public class KnowledgeDocument {

    /** 文档唯一 ID */
    private final String id;

    /** 文档标题 */
    private final String title;

    /** 文档正文 */
    private final String content;

    /** 分类标签（如 "退款政策"、"发货规则"） */
    private final String category;

    /** 关键词（用于 BM25 混合检索） */
    private final String keywords;

    /** 生效日期时间戳（毫秒），-1 表示永久 */
    private final long effectiveAt;

    /** 过期日期时间戳（毫秒），-1 表示永不过期 */
    private final long expireAt;

    /** 创建时间戳 */
    private final long createdAt;

    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.category = category;
        this.keywords = keywords;
        this.effectiveAt = effectiveAt;
        this.expireAt = expireAt;
        this.createdAt = System.currentTimeMillis();
    }

    /** 文档是否在有效期内 */
    public boolean isActive() {
        long now = System.currentTimeMillis();
        if (effectiveAt > 0 && now < effectiveAt) return false;
        if (expireAt > 0 && now > expireAt) return false;
        return true;
    }

    /** 获取用于嵌入的文本（标题 + 正文 + 关键词） */
    public String toEmbedText() {
        return title + "。\n" + content + "\n关键词：" + keywords;
    }

    // ------- Getters -------

}
