/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

/**
 * 摄取任务对外视图——进度 API 返回给调用方，不含内部可变引用。
 */
public record IngestionJobView(
        String jobId,
        String sourceName,
        String tenantId,
        String status,
        String statusLabel,
        int progress,
        int docCount,
        String errorMessage,
        String stageNote,
        int retryCount,
        long createdAt,
        long updatedAt,
        long finishedAt) {
}
