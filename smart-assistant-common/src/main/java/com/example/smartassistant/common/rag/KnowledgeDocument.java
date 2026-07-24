/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
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
 *
 * <p>治理能力补充（2026-07-07，对标字节 RAG 七连问）：</p>
 * <ul>
 *   <li>authorityLevel：来源权威性等级（L1 官方 &gt; L2 内部 &gt; L3 笔记 &gt; L4 外部），冲突处理时排序</li>
 *   <li>documentStatus：文档状态（ACTIVE 可检索 / SUPERSEDED 已取代 / QUARANTINED 隔离），治理隔离</li>
 * </ul>
 *
 * <p>RAG 生产化改造（2026-07-14，仅增不删）：新增 {@code sourceType} / {@code rawChecksum} /
 * {@code ingestBatchId} 三个字段，用于路由摄入管道（REQ-1）的溯源与变更检测。</p>
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

    // ==================== 治理能力字段（2026-07-07 补齐）====================

    /** 🔴 来源权威性等级：L1 官方 > L2 内部 > L3 笔记 > L4 外部，默认 L2_INTERNAL */
    private final AuthorityLevel authorityLevel;

    /** 🔴 文档状态：ACTIVE 可检索 / SUPERSEDED 已取代 / QUARANTINED 隔离，默认 ACTIVE */
    private final DocumentStatus documentStatus;

    // ═══════════════════════════════════════════════════════════
    // ⭐ 索引版本化字段（文章⑦索引重建与向量库同步）
    // ═══════════════════════════════════════════════════════════

    /**
     * 🟡 索引版本号：标识构建此 chunk 向量时所使用的索引策略/模型版本。
     * <p>
     * chunk切分策略变化、embedding模型变化、解析策略变化时应生成新的索引版本。
     * 检索时按 `active_index_version` 过滤，保证查到的向量是同一索引版本构建的。
     * </p>
     */
    private final String indexVersion;

    // ==================== ACL 细粒度字段（P3，对标文章⑤ 权限进入检索层）====================

    /** 🔴 ACL 细粒度：授权角色集合（空 = 租户内任意角色可见） */
    private final Set<String> authorizedRoles;

    /** 🔴 ACL 细粒度：授权用户集合（空 = 租户内任意用户可见） */
    private final Set<String> authorizedUsers;

    /** 🟡 ACL 细粒度：安全等级（0=公开；数字越大越敏感；用户 clearance 须 ≥ 此值方可查看） */
    private final int securityLevel;

    // ==================== 生产化摄入字段（RAG 生产化改造，仅增不删）====================

    /** 🟡 Parent-Child Chunking：块角色（PARENT 父块不嵌入 / CHILD 子块检索 / STANDALONE 独立块），默认 STANDALONE */
    private final ChunkRole chunkRole;

    /** 🟡 来源类型（PDF / WORD / HTML / MARKDOWN / TXT / IMAGE），由解析路由结果映射 */
    private final String sourceType;

    /** 🟡 原始文件 SHA-256 校验和（用于变更检测与去重） */
    private final String rawChecksum;

    /** 🟡 一次摄入任务批次 ID（UUID），关联同一批次的所有 chunk */
    private final String ingestBatchId;

    // ==================== 构造器 ====================

    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt) {
        this(id, title, content, category, keywords, effectiveAt, expireAt,
                "", "v1", "", 0, "", AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE);
    }

    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt,
                             String tenantId, String version,
                             String sourceUrl, int chunkIndex) {
        this(id, title, content, category, keywords, effectiveAt, expireAt,
                tenantId, version, sourceUrl, chunkIndex, "",
                AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE);
    }

    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt,
                             String tenantId, String version,
                             String sourceUrl, int chunkIndex,
                             String parentDocId) {
        this(id, title, content, category, keywords, effectiveAt, expireAt,
                tenantId, version, sourceUrl, chunkIndex, parentDocId,
                AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE);
    }

    /**
     * ⭐ 旧版全参构造器（不含 indexVersion），委托新版。
     */
    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt,
                             String tenantId, String version,
                             String sourceUrl, int chunkIndex,
                             String parentDocId,
                             AuthorityLevel authorityLevel, DocumentStatus documentStatus) {
        this(id, title, content, category, keywords, effectiveAt, expireAt,
                tenantId, version, sourceUrl, chunkIndex, parentDocId,
                authorityLevel, documentStatus, null);
    }

    /**
     * ⭐ 全参构造器（含治理能力字段 + 索引版本）。
     * <p>委托给含 ACL 细粒度字段的最全构造器，ACL 字段留空（公开）。</p>
     *
     * @param authorityLevel 来源权威性等级（null → L2_INTERNAL）
     * @param documentStatus 文档状态（null → ACTIVE）
     * @param indexVersion   索引版本（null → "v1"）
     */
    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt,
                             String tenantId, String version,
                             String sourceUrl, int chunkIndex,
                             String parentDocId,
                             AuthorityLevel authorityLevel, DocumentStatus documentStatus,
                             String indexVersion) {
        this(id, title, content, category, keywords, effectiveAt, expireAt,
                tenantId, version, sourceUrl, chunkIndex, parentDocId,
                authorityLevel, documentStatus, indexVersion,
                Set.of(), Set.of(), 0);
    }

    /**
     * ⭐ 含 sourceType 的构造器（parentDocId + sourceType），委托最全构造器。
     * <p>RAG 生产化修复（KI-1）：摄入管线经此把 contentType 映射的 sourceType 写入文档，
     * 使 DocumentValidator 不再对所有文档判 UNKNOWN_SOURCE。</p>
     */
    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt,
                             String tenantId, String version,
                             String sourceUrl, int chunkIndex,
                             String parentDocId, String sourceType) {
        this(id, title, content, category, keywords, effectiveAt, expireAt,
                tenantId, version, sourceUrl, chunkIndex, parentDocId,
                AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE, null,
                Set.of(), Set.of(), 0, ChunkRole.STANDALONE,
                sourceType, "", "");
    }

    /**
     * ⭐ 含 chunkRole 的构造器（Parent-Child 双粒度分块：父块 PARENT / 子块 CHILD）。
     * <p>委托最全构造器，治理字段取默认值，chunkRole 显式传入以区分父块（不嵌入）与子块（检索）。</p>
     */
    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt,
                             String tenantId, String version,
                             String sourceUrl, int chunkIndex,
                             String parentDocId, ChunkRole chunkRole, String sourceType) {
        this(id, title, content, category, keywords, effectiveAt, expireAt,
                tenantId, version, sourceUrl, chunkIndex, parentDocId,
                AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE, null,
                Set.of(), Set.of(), 0,
                chunkRole, sourceType, "", "");
    }

    /**
     * ⭐ 含 chunkRole 的"治理 + 索引版本 + 摄入"全参构造器（Parent-Child 双写经摄入重映射时保留块角色）。
     * <p>摄入管线的版本化重映射 / PII 脱敏会重建 KnowledgeDocument，必须显式透传 {@code chunkRole}，
     * 否则父块会被误判为 STANDALONE 而错误嵌入、参与向量检索。</p>
     */
    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt,
                             String tenantId, String version,
                             String sourceUrl, int chunkIndex,
                             String parentDocId,
                             AuthorityLevel authorityLevel, DocumentStatus documentStatus,
                             String indexVersion, ChunkRole chunkRole, String sourceType) {
        this(id, title, content, category, keywords, effectiveAt, expireAt,
                tenantId, version, sourceUrl, chunkIndex, parentDocId,
                authorityLevel, documentStatus, indexVersion,
                Set.of(), Set.of(), 0, chunkRole, sourceType, "", "");
    }

    /**
     * ⭐ 含 sourceType 的全参构造器（parentDocId + authorityLevel + documentStatus + indexVersion + sourceType），
     * 委托最全构造器。摄入管线在重映射/清洗阶段用此构造器保留治理字段并补 sourceType。
     */
    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt,
                             String tenantId, String version,
                             String sourceUrl, int chunkIndex,
                             String parentDocId,
                             AuthorityLevel authorityLevel, DocumentStatus documentStatus,
                             String indexVersion, String sourceType) {
        this(id, title, content, category, keywords, effectiveAt, expireAt,
                tenantId, version, sourceUrl, chunkIndex, parentDocId,
                authorityLevel, documentStatus, indexVersion,
                Set.of(), Set.of(), 0, ChunkRole.STANDALONE,
                sourceType, "", "");
    }

    /**
     * ⭐ 全参构造器（含治理能力字段 + 索引版本 + ACL 细粒度字段）。
     *
     * @param authorityLevel    来源权威性等级（null → L2_INTERNAL）
     * @param documentStatus    文档状态（null → ACTIVE）
     * @param indexVersion      索引版本（null → "v1"）
     * @param authorizedRoles   授权角色集合（null/空 = 租户内任意角色可见）
     * @param authorizedUsers   授权用户集合（null/空 = 租户内任意用户可见）
     * @param securityLevel     安全等级（0=公开；越大越敏感）
     */
    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt,
                             String tenantId, String version,
                             String sourceUrl, int chunkIndex,
                             String parentDocId,
                             AuthorityLevel authorityLevel, DocumentStatus documentStatus,
                             String indexVersion,
                             Set<String> authorizedRoles,
                             Set<String> authorizedUsers,
                             int securityLevel) {
        // ⭐ 向后兼容：旧调用方（不含生产化摄入三字段）委托到最全构造器，默认空串
        this(id, title, content, category, keywords, effectiveAt, expireAt,
                tenantId, version, sourceUrl, chunkIndex, parentDocId,
                authorityLevel, documentStatus, indexVersion,
                authorizedRoles, authorizedUsers, securityLevel, ChunkRole.STANDALONE,
                "", "", "");
    }

    /**
     * ⭐⭐ 最全构造器（含治理能力字段 + 索引版本 + ACL 细粒度字段 + 生产化摄入三字段）。
     * <p>RAG 生产化改造（2026-07-14）：在既有最全构造器基础上新增
     * {@code sourceType} / {@code rawChecksum} / {@code ingestBatchId} 三个字段，
     * 仅增不删，旧构造器链通过空串默认值委托至此。</p>
     *
     * @param authorityLevel    来源权威性等级（null → L2_INTERNAL）
     * @param documentStatus    文档状态（null → ACTIVE）
     * @param indexVersion      索引版本（null → "v1"）
     * @param authorizedRoles   授权角色集合（null/空 = 租户内任意角色可见）
     * @param authorizedUsers   授权用户集合（null/空 = 租户内任意用户可见）
     * @param securityLevel     安全等级（0=公开；越大越敏感）
     * @param sourceType        来源类型（PDF/WORD/HTML/MARKDOWN/TXT/IMAGE，null → ""）
     * @param rawChecksum       原始文件 SHA-256（null → ""）
     * @param ingestBatchId     摄入批次 ID（null → ""）
     */
    public KnowledgeDocument(String id, String title, String content,
                             String category, String keywords,
                             long effectiveAt, long expireAt,
                             String tenantId, String version,
                             String sourceUrl, int chunkIndex,
                             String parentDocId,
                             AuthorityLevel authorityLevel, DocumentStatus documentStatus,
                             String indexVersion,
                             Set<String> authorizedRoles,
                             Set<String> authorizedUsers,
                             int securityLevel,
                             ChunkRole chunkRole,
                             String sourceType, String rawChecksum, String ingestBatchId) {
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
        this.authorityLevel = authorityLevel != null ? authorityLevel : AuthorityLevel.L2_INTERNAL;
        this.documentStatus = documentStatus != null ? documentStatus : DocumentStatus.ACTIVE;
        this.indexVersion = indexVersion != null ? indexVersion : "v1";
        this.authorizedRoles = authorizedRoles != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(authorizedRoles))
                : Set.of();
        this.authorizedUsers = authorizedUsers != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(authorizedUsers))
                : Set.of();
        this.securityLevel = securityLevel;
        this.chunkRole = chunkRole != null ? chunkRole : ChunkRole.STANDALONE;
        this.sourceType = sourceType != null ? sourceType : "";
        this.rawChecksum = rawChecksum != null ? rawChecksum : "";
        this.ingestBatchId = ingestBatchId != null ? ingestBatchId : "";
    }

    /** 文档是否在有效期内且未被隔离 */
    public boolean isActive() {
        if (documentStatus == DocumentStatus.QUARANTINED) return false;
        long now = System.currentTimeMillis();
        if (effectiveAt > 0 && now < effectiveAt) return false;
        if (expireAt > 0 && now > expireAt) return false;
        return true;
    }

    /** 文档是否可被检索返回（ACTIVE 且有效期内） */
    public boolean isRetrievable() {
        return documentStatus.isRetrievable() && isActive();
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
    public AuthorityLevel getAuthorityLevel() { return authorityLevel; }
    public DocumentStatus getDocumentStatus() { return documentStatus; }
    public String getIndexVersion() { return indexVersion; }
    public Set<String> getAuthorizedRoles() { return authorizedRoles; }
    public Set<String> getAuthorizedUsers() { return authorizedUsers; }
    public int getSecurityLevel() { return securityLevel; }

    // ==================== 生产化摄入字段 Getters（RAG 生产化改造，仅增不删）====================

    /** Parent-Child 块角色（PARENT 父块 / CHILD 子块 / STANDALONE 独立块） */
    public ChunkRole getChunkRole() { return chunkRole; }

    /** 来源类型（PDF / WORD / HTML / MARKDOWN / TXT / IMAGE） */
    public String getSourceType() { return sourceType; }

    /** 原始文件 SHA-256 校验和 */
    public String getRawChecksum() { return rawChecksum; }

    /** 一次摄入任务批次 ID */
    public String getIngestBatchId() { return ingestBatchId; }

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
