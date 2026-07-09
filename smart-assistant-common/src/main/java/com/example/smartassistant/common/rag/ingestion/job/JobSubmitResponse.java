/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

/**
 * 摄取提交响应——返回任务 ID 与受理状态（202 语义：已受理，异步执行中）。
 */
public record JobSubmitResponse(
        String jobId,
        String status,
        String statusLabel,
        boolean accepted) {
}
