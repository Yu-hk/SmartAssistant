/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.AuthorityLevel;
import com.example.smartassistant.common.rag.DocumentStatus;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DocumentValidator 单元测试——验证脏数据判废规则（架构 §5 #8，PRD §阶段1 验收③）。
 * <p>
 * 判废标准（任一即废）：① 正文非空；② 分类非空；③ 时效合法；④ ACL 合法；⑤ sourceType 已知。
 * 命中返回 {@link ValidationResult#reject}，由调用方转入复核队列。
 * </p>
 * <p>
 * 注意：本测试只验证校验器自身逻辑。REQ-1「合规文档断言通过」的端到端验证见
 * {@link KnowledgeIngestionComplianceTest}（该测试会暴露 sourceType 未绑定的源码缺陷）。
 * </p>
 */
class DocumentValidatorTest {

    private final DocumentValidator validator = new DocumentValidator();

    /** 构造一篇知识文档（21 参最全构造器）。 */
    private KnowledgeDocument doc(String content, String category, String sourceType,
                                  long effectiveAt, long expireAt, int securityLevel) {
        return new KnowledgeDocument("d1", "t", content, category, "k",
                effectiveAt, expireAt, "", "v1", "", 0, "",
                AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE, "v1",
                Set.of(), Set.of(), securityLevel,
                sourceType, "", "");
    }

    @Test
    void nullDocRejected() {
        ValidationResult r = validator.validate(null);
        assertFalse(r.isPassed());
        assertEquals("NULL_DOC", r.getCode());
    }

    @Test
    void emptyContentRejected() {
        ValidationResult r = validator.validate(doc("", "退款政策", "WORD", -1, -1, 0));
        assertFalse(r.isPassed());
        assertEquals("EMPTY_CONTENT", r.getCode());
    }

    @Test
    void emptyCategoryRejected() {
        ValidationResult r = validator.validate(doc("合规的退款政策正文内容足够长以通过校验。", "", "WORD", -1, -1, 0));
        assertFalse(r.isPassed());
        assertEquals("EMPTY_CATEGORY", r.getCode());
    }

    @Test
    void unknownSourceTypeRejected() {
        ValidationResult empty = validator.validate(doc("合规正文内容足够长。", "退款政策", "", -1, -1, 0));
        assertFalse(empty.isPassed());
        assertEquals("UNKNOWN_SOURCE", empty.getCode());

        ValidationResult weird = validator.validate(doc("合规正文内容足够长。", "退款政策", "XYZ", -1, -1, 0));
        assertFalse(weird.isPassed());
        assertEquals("UNKNOWN_SOURCE", weird.getCode());
    }

    @Test
    void expireBeforeEffectiveRejected() {
        // expireAt <= effectiveAt
        ValidationResult r = validator.validate(doc("合规正文内容足够长。", "退款政策", "WORD", 200L, 100L, 0));
        assertFalse(r.isPassed());
        assertEquals("EXPIRE_BEFORE_EFFECTIVE", r.getCode());
    }

    @Test
    void effectiveTooFarRejected() {
        // 生效时间超过 10 年（约 3650 天）后；expireAt 需 > 0 才会触发时效校验（设计如此），
        // 且必须晚于 effectiveAt 以避免先命中 EXPIRE_BEFORE_EFFECTIVE。
        long tooFar = System.currentTimeMillis() + 4000L * 86400000L;
        long expire = tooFar + 10L * 86400000L; // expireAt 晚于 effectiveAt
        ValidationResult r = validator.validate(doc("合规正文内容足够长。", "退款政策", "WORD", tooFar, expire, 0));
        assertFalse(r.isPassed());
        assertEquals("EFFECTIVE_TOO_FAR", r.getCode());
    }

    @Test
    void invalidSecurityLevelRejected() {
        ValidationResult high = validator.validate(doc("合规正文内容足够长。", "退款政策", "WORD", -1, -1, 11));
        assertFalse(high.isPassed());
        assertEquals("INVALID_SECURITY", high.getCode());

        ValidationResult low = validator.validate(doc("合规正文内容足够长。", "退款政策", "WORD", -1, -1, -1));
        assertFalse(low.isPassed());
        assertEquals("INVALID_SECURITY", low.getCode());
    }

    @Test
    void compliantDocWithKnownSourceTypePasses() {
        // 内容/分类/时效/ACL 均合规 + 已知 sourceType → 应通过
        ValidationResult r = validator.validate(
                doc("合规的退款政策正文内容足够长以通过校验与时效检查。", "退款政策", "WORD", -1, -1, 0));
        assertTrue(r.isPassed(), "内容/分类/时效/ACL/sourceType 均合规的文档应通过校验");
        assertEquals("PASS", r.getCode());
    }
}
