/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识文档——RAG 检索的基本单元。
 * <p>
 * 包含文档内容、元信息、分类标签、时效性字段、权限标签（ACL）、版本号。
 * 参考字节面试考点：向量数据库至少需要 6 类字段——embedding、chunk_text、
 * doc_id、metadata、权限标签(ACL)、版本号，每一类对应一种生产事故。
 * </p>
 *
 * <p>版本控制约定：</p>
 * <ul>
 *   <li>docId 格式：{baseId}[-v{version}][-s{seq}]，例如 "ORD-REFUND-001-v2-s3"</li>
 *   <li>version 字段格式：v1, v2, v3... 或日期格式 2026-01</li>
 *   <li>isSupersededBy(other): 同 baseId 且版本更新时返回 true</li>
 * </ul>
 */
public class KnowledgeDocument {

    /** 版本号提取模式（支持 v1, v2.1, 2026-01 等格式） */
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)(?:\\.(\\d+))?");

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

    /** 🟡 Parent-Child Chunking：父块 ID，子块通过此字段关联到父块以获取完整上下文 */
    private final String parentDocId;

    // ==================== 构造器 ====================

    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt) {
        this(id, title, content, category, keywords, effectiveAt, expireAt,
                "", "v1", "", 0, "");
    }

    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt,
                             String tenantId, String version,
                             String sourceUrl, int chunkIndex) {
        this(id, title, content, category, keywords, effectiveAt, expireAt,
                tenantId, version, sourceUrl, chunkIndex, "");
    }

    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt,
                             String tenantId, String version,
                             String sourceUrl, int chunkIndex,
                             String parentDocId) {
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
        this.parentDocId = parentDocId != null ? parentDocId : "";
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
    public String getParentDocId() { return parentDocId; }

    // ==================== 版本控制方法 ====================

    /**
     * 获取基础文档 ID（去除版本和 chunk 后缀）。
     * <p>
     * 例如 "ORD-REFUND-001-v2-s3" → "ORD-REFUND-001"。
     * 用于判断不同版本的文档是否属于同一内容实体。
     * </p>
     */
    public String getBaseDocId() {
        String base = id;
        // 去除最后的 "-g{seq}" 或 "-c{chunk}" 块编号
        base = base.replaceAll("-g\\d+(-c\\d+)?$", "");
        // 去除版本后缀 "-v{N}"
        base = base.replaceAll("-v\\d+(\\.\\d+)?$", "");
        return base;
    }

    /**
     * 判断本文档是否被另一文档取代。
     * <p>
     * 同 baseDocId，版本更高，且版本字段非默认时返回 true。
     * 用于 reindex 时清理旧版本，或 composeScore 中优先返回新版。
     * </p>
     *
     * @param other 另一个文档
     * @return 如果 other 是本文档的新版则返回 true
     */
    public boolean isSupersededBy(KnowledgeDocument other) {
        if (other == null) return false;
        // 不同 baseId 不构成取代关系
        if (!this.getBaseDocId().equals(other.getBaseDocId())) return false;
        // 取自身版本和对方版本
        int[] thisVer = parseVersion(this.version);
        int[] otherVer = parseVersion(other.version);
        // 如果没有有效版本号，默认不被取代
        if (thisVer == null || otherVer == null) return false;
        // 比较主版本号和次版本号
        if (otherVer[0] != thisVer[0]) return otherVer[0] > thisVer[0];
        if (otherVer.length > 1 && thisVer.length > 1) return otherVer[1] > thisVer[1];
        return false;
    }

    /**
     * 获取文档版本优先级（数字越大越新）。
     * <p>
     * 用于 composeScore 中作为排序因子。
     * 默认 v1 返回 1.0，v2 返回 2.0，以此类推。
     * 无法解析版本时返回 0。
     * </p>
     */
    public double getVersionPriority() {
        int[] ver = parseVersion(this.version);
        if (ver == null) return 0;
        double priority = ver[0];
        if (ver.length > 1) priority += ver[1] / 100.0;
        return priority;
    }

    /** 解析版本号字符串为数字数组 */
    private static int[] parseVersion(String version) {
        if (version == null || version.isBlank() || "v1".equals(version)) return null;
        Matcher m = VERSION_PATTERN.matcher(version);
        if (m.find()) {
            int major = Integer.parseInt(m.group(1));
            if (m.group(2) != null) {
                return new int[]{major, Integer.parseInt(m.group(2))};
            }
            return new int[]{major};
        }
        return null;
    }
}
