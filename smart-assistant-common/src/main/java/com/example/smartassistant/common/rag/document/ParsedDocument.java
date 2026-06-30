/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import java.util.Objects;

/**
 * 解析后的文档单元——文档预处理管线的统一输出。
 * <p>
 * 参考 RAG 文章的生产实践：一个 chunk 不能只有文本，
 * 还应包含 source、page、section、doc_id、version、
 * updated_at、permission、effective_time、content_hash
 * 等元数据，用于权限过滤、版本选择、引用溯源和问题回放。
 * </p>
 */
public class ParsedDocument {

    /** 文档原始 ID（如数据库主键或文件路径哈希） */
    private final String docId;

    /** 文档标题 */
    private final String title;

    /** 文档段落正文 */
    private final String content;

    /** 来源文件路径或 URL */
    private final String sourceUrl;

    /** 来源页号（PDF/图片类文档），-1 表示不适用 */
    private final int pageNumber;

    /** 所在章节（如 "3.2 退款流程"），可能为空 */
    private final String section;

    /** 文档类型（pdf / word / html / txt） */
    private final String contentType;

    /** 租户隔离 ID（ACL），空字符串表示公开 */
    private final String tenantId;

    /** 版本号（如 "v2"），默认 "v1" */
    private final String version;

    /** 生效时间（毫秒时间戳），-1 表示永久有效 */
    private final long effectiveAt;

    /** 过期时间（毫秒时间戳），-1 表示永不过期 */
    private final long expireAt;

    /** 内容哈希（SHA-256），用于变更检测和去重 */
    private final String contentHash;

    /** 分类标签（如 "退款政策"、"产品说明"） */
    private final String category;

    /** 自定义关键词（用于 BM25 增强检索） */
    private final String keywords;

    // ==================== 构造器 ====================

    private ParsedDocument(Builder builder) {
        this.docId = builder.docId;
        this.title = builder.title != null ? builder.title : "";
        this.content = builder.content != null ? builder.content : "";
        this.sourceUrl = builder.sourceUrl != null ? builder.sourceUrl : "";
        this.pageNumber = builder.pageNumber;
        this.section = builder.section != null ? builder.section : "";
        this.contentType = builder.contentType != null ? builder.contentType : "txt";
        this.tenantId = builder.tenantId != null ? builder.tenantId : "";
        this.version = builder.version != null ? builder.version : "v1";
        this.effectiveAt = builder.effectiveAt;
        this.expireAt = builder.expireAt;
        this.contentHash = builder.contentHash != null ? builder.contentHash : "";
        this.category = builder.category != null ? builder.category : "";
        this.keywords = builder.keywords != null ? builder.keywords : "";
    }

    // ==================== Getters ====================

    public String getDocId() { return docId; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getSourceUrl() { return sourceUrl; }
    public int getPageNumber() { return pageNumber; }
    public String getSection() { return section; }
    public String getContentType() { return contentType; }
    public String getTenantId() { return tenantId; }
    public String getVersion() { return version; }
    public long getEffectiveAt() { return effectiveAt; }
    public long getExpireAt() { return expireAt; }
    public String getContentHash() { return contentHash; }
    public String getCategory() { return category; }
    public String getKeywords() { return keywords; }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String docId;
        private String title;
        private String content;
        private String sourceUrl;
        private int pageNumber = -1;
        private String section;
        private String contentType = "txt";
        private String tenantId = "";
        private String version = "v1";
        private long effectiveAt = -1;
        private long expireAt = -1;
        private String contentHash;
        private String category;
        private String keywords;

        public Builder docId(String val) { this.docId = val; return this; }
        public Builder title(String val) { this.title = val; return this; }
        public Builder content(String val) { this.content = val; return this; }
        public Builder sourceUrl(String val) { this.sourceUrl = val; return this; }
        public Builder pageNumber(int val) { this.pageNumber = val; return this; }
        public Builder section(String val) { this.section = val; return this; }
        public Builder contentType(String val) { this.contentType = val; return this; }
        public Builder tenantId(String val) { this.tenantId = val; return this; }
        public Builder version(String val) { this.version = val; return this; }
        public Builder effectiveAt(long val) { this.effectiveAt = val; return this; }
        public Builder expireAt(long val) { this.expireAt = val; return this; }
        public Builder contentHash(String val) { this.contentHash = val; return this; }
        public Builder category(String val) { this.category = val; return this; }
        public Builder keywords(String val) { this.keywords = val; return this; }

        public ParsedDocument build() {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(content, "content must not be null");
            return new ParsedDocument(this);
        }
    }

    @Override
    public String toString() {
        return "ParsedDocument{docId='" + docId + "', title='" + title
                + "', contentType='" + contentType
                + "', page=" + pageNumber + ", section='" + section + "'}";
    }
}
