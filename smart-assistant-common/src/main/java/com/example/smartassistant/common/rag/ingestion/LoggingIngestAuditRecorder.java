/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认审计实现：结构化日志（字段化，便于日志采集系统解析）。
 * <p>
 * 不引入数据库依赖，任何部署环境均可直接使用；如需长期留存，
 * 实现 {@link IngestAuditRecorder} 接口写入 {@code ingest_audit} 表即可。
 * </p>
 */
public class LoggingIngestAuditRecorder implements IngestAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(LoggingIngestAuditRecorder.class);

    @Override
    public void record(IngestAuditEvent event) {
        if (event == null) return;
        log.info("[IngestAudit] operator={} action={} docId={} baseDocId={} version={} ts={} detail={}",
                event.operator(), event.action(), event.docId(),
                event.baseDocId(), event.version(), event.timestamp(), event.detail());
    }
}
