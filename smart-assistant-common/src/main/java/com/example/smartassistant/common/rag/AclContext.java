/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import org.slf4j.MDC;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 检索层访问控制上下文（文章⑤：权限进入检索层，服务端生成 filter）。
 * <p>
 * 在租户隔离（tenantId）之上补齐细粒度维度：
 * <ul>
 *   <li>{@code roles}：用户所属角色集合，文档 {@code authorizedRoles} 为空表示租户内任意角色可见；</li>
 *   <li>{@code userId}：用户标识，文档 {@code authorizedUsers} 为空表示租户内任意用户可见；</li>
 *   <li>{@code securityClearance}：用户安全许可等级，必须 ≥ 文档 {@code securityLevel} 方可查看。</li>
 * </ul>
 *
 * <p>设计要点：filter 完全由服务端根据请求身份生成，绝不信任客户端传入的过滤条件。</p>
 */
public class AclContext {

    /** 无身份/公开上下文（等价租户隔离的公开检索） */
    public static final AclContext PUBLIC = new AclContext("", "", Set.of(), 0);

    private final String tenantId;
    private final String userId;
    private final Set<String> roles;
    private final int securityClearance;

    private AclContext(String tenantId, String userId, Set<String> roles, int securityClearance) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.userId = userId != null ? userId : "";
        this.roles = roles != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(roles))
                : Set.of();
        this.securityClearance = securityClearance;
    }

    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public Set<String> getRoles() { return roles; }
    public int getSecurityClearance() { return securityClearance; }

    /** 是否拥有某角色 */
    public boolean hasRole(String role) {
        return role != null && roles.contains(role);
    }

    // ==================== 工厂 ====================

    /** 仅按租户构造（细粒度维度留空，等价于原租户隔离语义） */
    public static AclContext forTenant(String tenantId) {
        return new AclContext(tenantId != null ? tenantId : "", "", Set.of(), 0);
    }

    /** 公开上下文 */
    public static AclContext publicContext() {
        return forTenant(KnowledgeBase.PUBLIC_TENANT);
    }

    /**
     * ⭐ 从请求上下文 MDC 构建细粒度 ACL。
     * <p>支持的 MDC Key：tenantId / userId / aclRoles(逗号分隔) / securityClearance(整数)。
     * 缺省时退化为公开上下文，保证无身份信息时检索行为不变（向后兼容）。</p>
     */
    public static AclContext fromMdc() {
        String tenantId = MDC.get("tenantId");
        String userId = MDC.get("userId");
        String rolesStr = MDC.get("aclRoles");
        String clearanceStr = MDC.get("securityClearance");

        Set<String> roles = Set.of();
        if (rolesStr != null && !rolesStr.isBlank()) {
            roles = Collections.unmodifiableSet(new LinkedHashSet<>(
                    Arrays.asList(rolesStr.split(","))));
        }
        int clearance = 0;
        if (clearanceStr != null && !clearanceStr.isBlank()) {
            try {
                clearance = Integer.parseInt(clearanceStr.trim());
            } catch (NumberFormatException e) {
                clearance = 0;
            }
        }
        return new AclContext(tenantId, userId, roles, clearance);
    }

    public static Builder builder() { return new Builder(); }

    // ==================== Builder ====================

    public static class Builder {
        private String tenantId = "";
        private String userId = "";
        private Set<String> roles;
        private int securityClearance = 0;

        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder userId(String v) { this.userId = v; return this; }
        public Builder roles(Set<String> v) { this.roles = v; return this; }
        public Builder securityClearance(int v) { this.securityClearance = v; return this; }
        public AclContext build() {
            return new AclContext(tenantId, userId, roles, securityClearance);
        }
    }

    // ==================== 等值/展示 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AclContext)) return false;
        AclContext that = (AclContext) o;
        return securityClearance == that.securityClearance
                && Objects.equals(tenantId, that.tenantId)
                && Objects.equals(userId, that.userId)
                && Objects.equals(roles, that.roles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, userId, roles, securityClearance);
    }

    @Override
    public String toString() {
        return "AclContext{tenantId='" + tenantId + "', userId='" + userId
                + "', roles=" + roles + ", securityClearance=" + securityClearance + "}";
    }
}
