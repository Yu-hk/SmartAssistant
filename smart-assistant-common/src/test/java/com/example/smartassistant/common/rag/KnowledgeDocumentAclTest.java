/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACL 细粒度字段单元测试（P3-A，对标文章⑤ 权限进入检索层）。
 * <p>验证 {@link KnowledgeDocument} 的 {@code authorizedRoles}/{@code authorizedUsers}/
 * {@code securityLevel} 三字段在默认构造器与全参构造器下的正确行为。</p>
 */
class KnowledgeDocumentAclTest {

    @Test
    void defaultConstructorsYieldPublicAcl() {
        // 旧版 7 参构造器（向后兼容）
        KnowledgeDocument doc = new KnowledgeDocument(
                "id1", "title", "content", "cat", "kw", -1, -1);
        assertTrue(doc.getAuthorizedRoles().isEmpty(), "默认授权角色应为空（租户内任意可见）");
        assertTrue(doc.getAuthorizedUsers().isEmpty(), "默认授权用户应为空（租户内任意可见）");
        assertEquals(0, doc.getSecurityLevel(), "默认安全等级应为 0（公开）");
    }

    @Test
    void oldFullConstructorYieldsPublicAcl() {
        // 不含 ACL 的全参构造器（15 参，含 indexVersion 之前）
        KnowledgeDocument doc = new KnowledgeDocument(
                "id1", "title", "content", "cat", "kw", -1, -1,
                "T1", "v1", "", 0, "",
                AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE);
        assertTrue(doc.getAuthorizedRoles().isEmpty());
        assertTrue(doc.getAuthorizedUsers().isEmpty());
        assertEquals(0, doc.getSecurityLevel());
    }

    @Test
    void fullAclConstructorSetsFields() {
        Set<String> roles = Set.of("admin", "ops");
        Set<String> users = Set.of("alice");
        KnowledgeDocument doc = new KnowledgeDocument(
                "id1", "title", "content", "cat", "kw", -1, -1,
                "T1", "v1", "", 0, "",
                AuthorityLevel.L1_OFFICIAL, DocumentStatus.ACTIVE,
                "idx-v2", roles, users, 3);
        assertEquals(roles, doc.getAuthorizedRoles());
        assertEquals(users, doc.getAuthorizedUsers());
        assertEquals(3, doc.getSecurityLevel());
        assertEquals("T1", doc.getTenantId());
        assertEquals("idx-v2", doc.getIndexVersion());
    }

    @Test
    void nullAclFieldsTreatedAsPublic() {
        KnowledgeDocument doc = new KnowledgeDocument(
                "id1", "title", "content", "cat", "kw", -1, -1,
                "T1", "v1", "", 0, "",
                AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE,
                null, null, null, 0);
        assertTrue(doc.getAuthorizedRoles().isEmpty());
        assertTrue(doc.getAuthorizedUsers().isEmpty());
        assertEquals(0, doc.getSecurityLevel());
    }

    @Test
    void securityLevelGatingSemantics() {
        // securityLevel=0 视为公开，任何 clearance 都可看（在 KB 过滤逻辑中验证，
        // 此处仅确认字段可承载语义值）
        KnowledgeDocument publicDoc = new KnowledgeDocument(
                "p", "t", "c", "cat", "kw", -1, -1, "T1", "v1", "", 0, "",
                AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE, null,
                Set.of(), Set.of(), 0);
        KnowledgeDocument secretDoc = new KnowledgeDocument(
                "s", "t", "c", "cat", "kw", -1, -1, "T1", "v1", "", 0, "",
                AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE, null,
                Set.of(), Set.of(), 5);
        assertEquals(0, publicDoc.getSecurityLevel());
        assertEquals(5, secretDoc.getSecurityLevel());
    }
}
