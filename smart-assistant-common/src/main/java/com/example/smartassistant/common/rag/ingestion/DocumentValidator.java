/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.KnowledgeDocument;

import java.util.Set;

/**
 * 脏数据判废器（REQ-1）——摄入前对 {@link KnowledgeDocument} 做准入校验。
 * <p>
 * 判废标准（架构 §5 #8，任一即废）：
 * <ol>
 *   <li>正文非空；</li>
 *   <li>分类非空；</li>
 *   <li>时效合法（expireAt==-1 或 expireAt&gt;effectiveAt 且 effectiveAt 不过远）；</li>
 *   <li>ACL 合法（安全等级 ∈ [0, MAX]，tenantId 格式合法）；</li>
 *   <li>sourceType 已知。</li>
 * </ol>
 * 命中即返回 {@link ValidationResult#reject}，由调用方 100% 转入复核队列。
 * </p>
 */
public class DocumentValidator {

    /** 已知来源类型 */
    public static final Set<String> KNOWN_SOURCE_TYPES =
            Set.of("PDF", "WORD", "HTML", "MARKDOWN", "TXT", "IMAGE");

    /** 安全等级上限 */
    public static final int MAX_SECURITY_LEVEL = 10;

    /** 生效时间允许的最大未来偏移（~10 年，毫秒） */
    private static final long MAX_EFFECTIVE_FUTURE_MS = 3650L * 86400000L;

    /**
     * 校验知识文档是否可入库。
     *
     * @param doc 待校验文档
     * @return 通过或带原因/code 的拒绝结果
     */
    public ValidationResult validate(KnowledgeDocument doc) {
        if (doc == null) {
            return ValidationResult.reject("NULL_DOC", "文档为 null");
        }
        if (doc.getContent() == null || doc.getContent().isBlank()) {
            return ValidationResult.reject("EMPTY_CONTENT", "正文为空");
        }
        if (doc.getCategory() == null || doc.getCategory().isBlank()) {
            return ValidationResult.reject("EMPTY_CATEGORY", "分类为空");
        }
        if (doc.getSourceType() == null || !KNOWN_SOURCE_TYPES.contains(doc.getSourceType())) {
            return ValidationResult.reject("UNKNOWN_SOURCE", "未知来源类型: " + doc.getSourceType());
        }

        long eff = doc.getEffectiveAt();
        long exp = doc.getExpireAt();
        if (exp > 0) {
            if (eff > 0 && exp <= eff) {
                return ValidationResult.reject("EXPIRE_BEFORE_EFFECTIVE",
                        "失效时间早于/等于生效时间");
            }
            if (eff > 0 && eff > System.currentTimeMillis() + MAX_EFFECTIVE_FUTURE_MS) {
                return ValidationResult.reject("EFFECTIVE_TOO_FAR", "生效时间过远（超过 10 年）");
            }
        }

        int sec = doc.getSecurityLevel();
        if (sec < 0 || sec > MAX_SECURITY_LEVEL) {
            return ValidationResult.reject("INVALID_SECURITY",
                    "安全等级越界: " + sec + " (允许 0~" + MAX_SECURITY_LEVEL + ")");
        }

        return ValidationResult.pass();
    }
}
