/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 合规审计记录器（REQ-3）——将命中事件落库到 PG {@code compliance_audit_log}。
 * <p>
 * 存储策略对齐 {@link com.example.smartassistant.common.rag.ingestion.ReviewQueueService}：
 * <ul>
 *   <li>优先写入 PG（{@code JdbcTemplate} 非空）；</li>
 *   <li>无 JdbcTemplate（内存 / 测试态）或写入异常时退化为内存 Map，保证任何环境可用；</li>
 *   <li>租户 ID 取自 {@link MDC}（与 {@code SafeGuardAdvisor} 一致），缺失为 {@code "-"}。</li>
 * </ul>
 * 接受度验收「命中写 compliance_audit_log」由本类保障。
 * </p>
 */
public class ComplianceAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(ComplianceAuditRecorder.class);

    /** 原始片段最大保留长度（TEXT 列不限，但审计展示截断即可） */
    private static final int SNIPPET_MAX = 500;

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, ComplianceAuditEvent> memory = new ConcurrentHashMap<>();
    /** 幂等建表标记（仅尝试一次） */
    private final AtomicBoolean tableEnsured = new AtomicBoolean(false);

    public ComplianceAuditRecorder() {
        this(null);
    }

    public ComplianceAuditRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记录一条合规命中审计。
     *
     * @param ruleId          主规则 ID
     * @param severity        严重度
     * @param strategyApplied 实际处置策略
     * @param original        原始文本（自动截断）
     * @param rewritten       改写后文本（无则为空）
     */
    public void record(String ruleId, String severity, String strategyApplied,
                       String original, String rewritten) {
        record(ruleId, severity, strategyApplied, original, rewritten, currentTenant());
    }

    /**
     * 记录一条合规命中审计（显式租户）。
     */
    public void record(String ruleId, String severity, String strategyApplied,
                       String original, String rewritten, String tenantId) {
        String id = UUID.randomUUID().toString();
        String origSnippet = snippet(original);
        String rewSnippet = snippet(rewritten);
        ComplianceAuditEvent event = new ComplianceAuditEvent(
                id, ruleId, severity, strategyApplied, origSnippet, rewSnippet, tenantId,
                System.currentTimeMillis());
        memory.put(id, event);

        if (jdbcTemplate != null) {
            try {
                ensureTable();
                jdbcTemplate.update(
                        "INSERT INTO compliance_audit_log "
                                + "(id, rule_id, severity, strategy_applied, original_snippet, "
                                + "rewritten_snippet, tenant_id, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        event.getId(), event.getRuleId(), event.getSeverity(),
                        event.getStrategyApplied(), event.getOriginalSnippet(),
                        event.getRewrittenSnippet(), event.getTenantId(), event.getCreatedAt());
            } catch (Exception e) {
                log.warn("[ComplianceAudit] 写入 PG 失败（已保留内存）: {}", e.getMessage());
            }
        }
        log.info("[ComplianceAudit] rule={} severity={} strategy={} tenant={}",
                ruleId, severity, strategyApplied, tenantId);
    }

    /** 内存留存数量（运维 / 测试可观测） */
    public int memorySize() {
        return memory.size();
    }

    /**
     * 幂等建表（PG 可用时确保 {@code compliance_audit_log} 存在）。
     * <p>不依赖 Flyway 自动迁移（生产可走 V1 脚本）；首次写入前尝试一次，
     * 失败则忽略（降级内存）。仅执行一次。</p>
     */
    private void ensureTable() {
        if (!tableEnsured.compareAndSet(false, true)) return;
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS compliance_audit_log ("
                    + "id VARCHAR(64) PRIMARY KEY, "
                    + "rule_id VARCHAR(32), "
                    + "severity VARCHAR(16), "
                    + "strategy_applied VARCHAR(16), "
                    + "original_snippet TEXT, "
                    + "rewritten_snippet TEXT, "
                    + "tenant_id VARCHAR(64) DEFAULT '', "
                    + "created_at BIGINT NOT NULL)");
        } catch (Exception e) {
            log.warn("[ComplianceAudit] 建表 compliance_audit_log 失败（可忽略，将降级内存）: {}", e.getMessage());
        }
    }

    /** 截断片段 */
    private static String snippet(String s) {
        if (s == null) return "";
        return s.length() <= SNIPPET_MAX ? s : s.substring(0, SNIPPET_MAX) + "...";
    }

    /** 当前租户（MDC，缺失兜底 "-"） */
    private static String currentTenant() {
        String t = MDC.get("tenantId");
        return (t != null && !t.isBlank()) ? t : "-";
    }
}
