/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 治理能力字段单元测试——AuthorityLevel / DocumentStatus / KnowledgeDocument 新字段。
 */
class GovernanceFieldTest {

    @Test
    void authorityLevelFromCode() {
        assertEquals(AuthorityLevel.L1_OFFICIAL, AuthorityLevel.fromCode("L1_OFFICIAL"));
        assertEquals(AuthorityLevel.L2_INTERNAL, AuthorityLevel.fromCode("L2"));
        assertEquals(AuthorityLevel.L2_INTERNAL, AuthorityLevel.fromCode(null)); // 默认 L2
        assertEquals(4, AuthorityLevel.L1_OFFICIAL.getRank());
        assertEquals(1, AuthorityLevel.L4_EXTERNAL.getRank());
    }

    @Test
    void authorityLevelFromRank() {
        assertEquals(AuthorityLevel.L3_NOTE, AuthorityLevel.fromRank(2));
        assertEquals(AuthorityLevel.L2_INTERNAL, AuthorityLevel.fromRank(99)); // 越界默认 L2
    }

    @Test
    void documentStatusFromCode() {
        assertEquals(DocumentStatus.QUARANTINED, DocumentStatus.fromCode("QUARANTINED"));
        assertEquals(DocumentStatus.ACTIVE, DocumentStatus.fromCode("unknown"));
        assertTrue(DocumentStatus.ACTIVE.isRetrievable());
        assertFalse(DocumentStatus.QUARANTINED.isRetrievable());
    }

    @Test
    void knowledgeDocumentDefaults() {
        KnowledgeDocument doc = new KnowledgeDocument(
                "id1", "title", "content", "cat", "kw", -1, -1);
        assertEquals(AuthorityLevel.L2_INTERNAL, doc.getAuthorityLevel());
        assertEquals(DocumentStatus.ACTIVE, doc.getDocumentStatus());
        assertTrue(doc.isRetrievable());
    }

    @Test
    void quarantinedExcludedFromRetrieval() {
        KnowledgeDocument doc = new KnowledgeDocument(
                "id1", "title", "content", "cat", "kw", -1, -1,
                "", "v1", "", 0, "", AuthorityLevel.L2_INTERNAL, DocumentStatus.QUARANTINED);
        assertFalse(doc.isActive());
        assertFalse(doc.isRetrievable());
    }

    @Test
    void supersededNotRetrievable() {
        KnowledgeDocument doc = new KnowledgeDocument(
                "id1", "title", "content", "cat", "kw", -1, -1,
                "", "v1", "", 0, "", AuthorityLevel.L2_INTERNAL, DocumentStatus.SUPERSEDED);
        assertFalse(doc.isRetrievable());
    }

    @Test
    void expiredNotRetrievable() {
        long now = System.currentTimeMillis();
        KnowledgeDocument doc = new KnowledgeDocument(
                "id1", "title", "content", "cat", "kw", -1, now - 1000,
                "", "v1", "", 0, "", AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE);
        assertFalse(doc.isRetrievable());
    }
}
