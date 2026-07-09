/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * InMemoryKnowledgeBase 细粒度 ACL 检索单元测试（P3-A，对标文章⑤）。
 * <p>使用 mock 的 {@link BgeEmbeddingModel} 返回恒定向量，使所有文档余弦相似度一致，
 * 从而隔离出 ACL 过滤逻辑本身（租户/安全等级/角色/用户 四层）的正确性。</p>
 */
@ExtendWith(MockitoExtension.class)
class InMemoryKnowledgeBaseAclTest {

    @Mock
    private BgeEmbeddingModel embeddingModel;

    private InMemoryKnowledgeBase kb;

    @BeforeEach
    void setUp() {
        // 所有文本返回同一向量 → 余弦相似度恒为 1.0，排除相似度干扰
        float[] constant = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingModel.embedding(anyString())).thenReturn(constant);
        kb = new InMemoryKnowledgeBase("acl-test", embeddingModel, null, null);
    }

    private KnowledgeDocument doc(String id, String tenant, int securityLevel,
                                  Set<String> roles, Set<String> users) {
        return new KnowledgeDocument(
                id, "标题", "正文内容", "cat", "kw", -1, -1,
                tenant, "v1", "", 0, "",
                AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE,
                null, roles, users, securityLevel);
    }

    private boolean idsContain(List<KnowledgeHit> hits, String id) {
        return hits.stream().anyMatch(h -> id.equals(h.getDocument().getId()));
    }

    // ───────────── 1. 租户隔离 ─────────────

    @Test
    void tenantIsolationEnforced() {
        kb.addDocument(doc("d-t1", "T1", 0, Set.of(), Set.of()));
        kb.addDocument(doc("d-t2", "T2", 0, Set.of(), Set.of()));

        // T1 用户只能看到 T1 文档
        List<KnowledgeHit> t1Hits = kb.search("任意", 5, AclContext.forTenant("T1"));
        assertTrue(idsContain(t1Hits, "d-t1"));
        assertFalse(idsContain(t1Hits, "d-t2"));

        // 公开上下文看不到任何租户专属文档
        List<KnowledgeHit> publicHits = kb.search("任意", 5, AclContext.PUBLIC);
        assertFalse(idsContain(publicHits, "d-t1"));
        assertFalse(idsContain(publicHits, "d-t2"));
    }

    // ───────────── 2. 安全等级 ─────────────

    @Test
    void securityLevelGating() {
        kb.addDocument(doc("secret", "T1", 5, Set.of(), Set.of()));

        // clearance=3 < 5 → 不可见
        assertFalse(idsContain(kb.search("任意", 5,
                AclContext.builder().tenantId("T1").securityClearance(3).build()), "secret"));
        // clearance=5 >= 5 → 可见
        assertTrue(idsContain(kb.search("任意", 5,
                AclContext.builder().tenantId("T1").securityClearance(5).build()), "secret"));
        // clearance=0 → 不可见（secret 非公开）
        assertFalse(idsContain(kb.search("任意", 5, AclContext.forTenant("T1")), "secret"));
    }

    @Test
    void publicSecurityLevelVisibleToAllClearance() {
        kb.addDocument(doc("open", "T1", 0, Set.of(), Set.of()));
        // securityLevel=0（公开）→ 任意 clearance 均可见
        assertTrue(idsContain(kb.search("任意", 5,
                AclContext.builder().tenantId("T1").securityClearance(0).build()), "open"));
        assertTrue(idsContain(kb.search("任意", 5,
                AclContext.builder().tenantId("T1").securityClearance(9).build()), "open"));
    }

    // ───────────── 3. 角色 ─────────────

    @Test
    void roleGating() {
        kb.addDocument(doc("role-admin", "T1", 0, Set.of("admin"), Set.of()));

        // 拥有 admin 角色 → 可见
        assertTrue(idsContain(kb.search("任意", 5,
                AclContext.builder().tenantId("T1").roles(Set.of("admin")).build()), "role-admin"));
        // 角色不符 → 不可见
        assertFalse(idsContain(kb.search("任意", 5,
                AclContext.builder().tenantId("T1").roles(Set.of("user")).build()), "role-admin"));
        // 无角色（匿名）→ 不可见（默认拒绝）
        assertFalse(idsContain(kb.search("任意", 5, AclContext.forTenant("T1")), "role-admin"));
    }

    @Test
    void emptyRoleDocVisibleToAnyRole() {
        kb.addDocument(doc("no-role", "T1", 0, Set.of(), Set.of()));
        // 文档未限定角色 → 任意角色（含无角色）可见
        assertTrue(idsContain(kb.search("任意", 5, AclContext.forTenant("T1")), "no-role"));
        assertTrue(idsContain(kb.search("任意", 5,
                AclContext.builder().tenantId("T1").roles(Set.of("user")).build()), "no-role"));
    }

    // ───────────── 4. 用户 ─────────────

    @Test
    void userGating() {
        kb.addDocument(doc("user-alice", "T1", 0, Set.of(), Set.of("alice")));

        // 指定用户 alice → 可见
        assertTrue(idsContain(kb.search("任意", 5,
                AclContext.builder().tenantId("T1").userId("alice").build()), "user-alice"));
        // 其他用户 bob → 不可见
        assertFalse(idsContain(kb.search("任意", 5,
                AclContext.builder().tenantId("T1").userId("bob").build()), "user-alice"));
        // 匿名 → 不可见（默认拒绝）
        assertFalse(idsContain(kb.search("任意", 5, AclContext.forTenant("T1")), "user-alice"));
    }

    @Test
    void emptyUserDocVisibleToAnyUser() {
        kb.addDocument(doc("no-user", "T1", 0, Set.of(), Set.of()));
        assertTrue(idsContain(kb.search("任意", 5, AclContext.forTenant("T1")), "no-user"));
        assertTrue(idsContain(kb.search("任意", 5,
                AclContext.builder().tenantId("T1").userId("bob").build()), "no-user"));
    }

    // ───────────── 5. 组合 + 默认拒绝 ─────────────

    @Test
    void combinedAclAndDefaultDeny() {
        // 高敏文档：仅 admin 角色 + alice 用户 + clearance>=5
        kb.addDocument(doc("top-secret", "T1", 5, Set.of("admin"), Set.of("alice")));

        // 匿名 → 不可见
        assertFalse(idsContain(kb.search("任意", 5, AclContext.forTenant("T1")), "top-secret"));
        // 仅 clearance 够，但无角色/用户 → 不可见（默认拒绝）
        assertFalse(idsContain(kb.search("任意", 5,
                AclContext.builder().tenantId("T1").securityClearance(9).build()), "top-secret"));
        // 角色对、用户错 → 不可见
        assertFalse(idsContain(kb.search("任意", 5,
                AclContext.builder().tenantId("T1").securityClearance(9)
                        .roles(Set.of("admin")).userId("bob").build()), "top-secret"));
        // 全部满足 → 可见
        assertTrue(idsContain(kb.search("任意", 5,
                AclContext.builder().tenantId("T1").securityClearance(9)
                        .roles(Set.of("admin")).userId("alice").build()), "top-secret"));
    }

    @Test
    void fromMdcBuildsAcl() {
        // 校验 AclContext.fromMdc 工厂可用（MDC 注入在运行期由拦截器完成，此处仅验证构造与默认拒绝）
        kb.addDocument(doc("d1", "T1", 0, Set.of(), Set.of()));
        AclContext acl = AclContext.fromMdc(); // 无 MDC → 退化为公开上下文
        assertFalse(idsContain(kb.search("任意", 5, acl), "d1"),
                "无身份上下文（公开）不应看到租户 T1 文档");
    }
}
