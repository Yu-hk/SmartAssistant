/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PgVectorKnowledgeBase.buildAclClause 单元测试（P3-A）。
 * <p>该方法为包级可见的静态方法，生成的 SQL 子句在检索前应用（文章⑤：权限进入检索层，
 * 服务端生成 filter）。无需数据库即可验证四层过滤（租户/安全等级/角色/用户）的拼接正确性，
 * 并确认"角色/用户子句始终输出"以实现默认拒绝。</p>
 */
class PgVectorBuildAclClauseTest {

    @Test
    void publicContextOnlyPublicDocs() {
        String clause = PgVectorKnowledgeBase.buildAclClause(AclContext.PUBLIC);
        assertTrue(clause.contains("tenant_id IS NULL OR tenant_id = ''"),
                "公开上下文应只匹配公开(空)租户文档: " + clause);
        assertTrue(clause.contains("security_level <= 0"), "安全等级默认 0: " + clause);
        // 始终输出角色/用户子句（匿名 → 排除限定了角色/用户的文档）
        assertTrue(clause.contains("authorized_roles"), clause);
        assertTrue(clause.contains("authorized_users"), clause);
    }

    @Test
    void tenantClauseEscapesInjection() {
        String clause = PgVectorKnowledgeBase.buildAclClause(AclContext.forTenant("T1' OR '1'='1"));
        // 单引号被转义，不应出现裸注入片段
        assertTrue(clause.contains("tenant_id = 'T1'' OR ''1''=''1'"),
                "租户值应被单引号转义以防注入: " + clause);
    }

    @Test
    void securityClearanceReflected() {
        String clause = PgVectorKnowledgeBase.buildAclClause(
                AclContext.builder().tenantId("T1").securityClearance(5).build());
        assertTrue(clause.contains("security_level <= 5"), "应反映用户安全许可等级: " + clause);
    }

    @Test
    void rolesRenderedAsPostgresArray() {
        String clause = PgVectorKnowledgeBase.buildAclClause(
                AclContext.builder().tenantId("T1").roles(Set.of("admin", "ops")).build());
        assertTrue(clause.contains("ARRAY["), "角色应渲染为 Postgres 数组字面量: " + clause);
        assertTrue(clause.contains("'admin'"), "应包含 admin 角色: " + clause);
        assertTrue(clause.contains("'ops'"), "应包含 ops 角色: " + clause);
        assertTrue(clause.contains("]::text[]"), "数组字面量应以 ::text[] 结尾: " + clause);
        assertTrue(clause.contains("&&"), "应使用数组交集操作符 &&: " + clause);
    }

    @Test
    void userIdRenderedWithAnyOperator() {
        String clause = PgVectorKnowledgeBase.buildAclClause(
                AclContext.builder().tenantId("T1").userId("alice").build());
        assertTrue(clause.contains("'alice' = ANY(authorized_users)"),
                "用户应使用 = ANY 匹配: " + clause);
    }

    @Test
    void emptyRoleOrUserUsesDefaultDeny() {
        // 匿名请求（无角色/无用户）：要求特定角色/用户的文档应被排除
        String clause = PgVectorKnowledgeBase.buildAclClause(AclContext.forTenant("T1"));
        // 角色子句对空角色集合：authorized_roles && ARRAY[]::text[]（交集为空 → 排除限定文档）
        assertTrue(clause.contains("authorized_roles && ARRAY[]::text[]"),
                "空角色应生成 ARRAY[] 交集（默认拒绝限定角色的文档）: " + clause);
        // 用户子句：'' = ANY(authorized_users)（匿名 → 排除限定用户的文档）
        assertTrue(clause.contains("'' = ANY(authorized_users)"),
                "匿名用户应生成 '' = ANY（默认拒绝限定用户的文档）: " + clause);
    }
}
