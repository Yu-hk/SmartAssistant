/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

/**
 * 知识文档——RAG 检索的基本单元。
 * <p>
 * 包含文档内容、元信息、分类标签、时效性字段、权限标签（ACL）、版本号。
 * 参考字节面试考点：向量数据库至少需要 6 类字段——embedding、chunk_text、
 * doc_id、metadata、权限标签(ACL)、版本号，每一类对应一种生产事故。
 * </p>
 */
public class KnowledgeDocument {

    // ==================== 原有字段 ====================

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

    // ==================== 新增生产字段（参考字节面试 6 类字段）====================

    /** 🔴 ACL：租户 ID（权限过滤），默认空字符串表示公开 */
    private final String tenantId;

    /** 🔴 版本号：灰度发布 + 回滚（如 "v3"），默认 "v1" */
    private final String version;

    /** 🟡 来源 URL：引用回链），默认空字符串 */
    private final String sourceUrl;

    /** 🟡 Chunk 序号：跨段拼接），默认 0 */
    private final int chunkIndex;

    // ==================== 构造器 ====================

    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt) {
        this(id, title, content, category, keywords, effectiveAt, expireAt,
                "", "v1", "", 0);
    }

    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt,
                             String tenantId, String version,
                             String sourceUrl, int chunkIndex) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.category = category;
        this.keywords = keywords;
        this.effectiveAt = effectiveAt;
        this.expireAt = expireAt;
        this.createdAt = System.currentTimeMillis();
        this.tenantId = tenantId != null ? tenantId : "";
        this.version = version != null ? version : "v1";
        this.sourceUrl = sourceUrl != null ? sourceUrl : "";
        this.chunkIndex = chunkIndex;
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

    // ==================== Getters ====================

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getCategory() { return category; }
    public String getKeywords() { return keywords; }
    public long getEffectiveAt() { return effectiveAt; }
    public long getExpireAt() { return expireAt; }
    public long getCreatedAt() { return createdAt; }
    public String getTenantId() { return tenantId; }
    public String getVersion() { return version; }
    public String getSourceUrl() { return sourceUrl; }
    public int getChunkIndex() { return chunkIndex; }
}
