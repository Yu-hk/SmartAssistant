/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.compliance;

import java.time.Instant;

/**
 * 合规审计事件（REQ-3）——命中规则后的结构化审计记录。
 * <p>
 * 由 {@link ComplianceAuditRecorder} 落库到 PG {@code compliance_audit_log}（无 JdbcTemplate 时内存留存），
 * 用于合规回溯、误杀率统计与人工复核。所有字段均不可变，线程安全。
 * </p>
 */
public final class ComplianceAuditEvent {

    /** 事件唯一 ID（UUID） */
    private final String id;

    /** 命中规则 ID（多规则命中时取最高严重度的主规则） */
    private final String ruleId;

    /** 严重度：HIGH / MEDIUM / LOW */
    private final String severity;

    /** 实际处置策略：REWRITE / BLOCK / WARN */
    private final String strategyApplied;

    /** 原始文本片段（截断，避免超长） */
    private final String originalSnippet;

    /** 改写后文本片段（无改写则为空） */
    private final String rewrittenSnippet;

    /** 租户 ID（取自 MDC，缺失为 "-") */
    private final String tenantId;

    /** 事件发生时间（毫秒） */
    private final long createdAt;

    public ComplianceAuditEvent(String id, String ruleId, String severity, String strategyApplied,
                                String originalSnippet, String rewrittenSnippet, String tenantId,
                                long createdAt) {
        this.id = id != null ? id : "";
        this.ruleId = ruleId != null ? ruleId : "";
        this.severity = severity != null ? severity : "";
        this.strategyApplied = strategyApplied != null ? strategyApplied : "";
        this.originalSnippet = originalSnippet != null ? originalSnippet : "";
        this.rewrittenSnippet = rewrittenSnippet != null ? rewrittenSnippet : "";
        this.tenantId = tenantId != null ? tenantId : "-";
        this.createdAt = createdAt > 0 ? createdAt : Instant.now().toEpochMilli();
    }

    public String getId() { return id; }
    public String getRuleId() { return ruleId; }
    public String getSeverity() { return severity; }
    public String getStrategyApplied() { return strategyApplied; }
    public String getOriginalSnippet() { return originalSnippet; }
    public String getRewrittenSnippet() { return rewrittenSnippet; }
    public String getTenantId() { return tenantId; }
    public long getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "ComplianceAuditEvent(id=" + id + ", ruleId=" + ruleId
                + ", severity=" + severity + ", strategy=" + strategyApplied
                + ", tenant=" + tenantId + ")";
    }
}
