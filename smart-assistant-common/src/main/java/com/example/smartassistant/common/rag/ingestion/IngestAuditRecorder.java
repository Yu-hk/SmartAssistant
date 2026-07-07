/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

/**
 * 入库操作审计记录器（P0，2026-07-07）。
 * <p>
 * 默认实现为结构化日志（{@link LoggingIngestAuditRecorder}），便于 ELK 采集；
 * 生产环境可替换为数据库持久化实现（如 {@code PgIngestAuditRecorder}）以满足合规留存。
 * </p>
 */
public interface IngestAuditRecorder {

    /**
     * 记录一次入库相关操作。
     *
     * @param event 审计事件（不可为 null）
     */
    void record(IngestAuditEvent event);
}
