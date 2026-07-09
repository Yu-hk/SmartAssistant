/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

import jakarta.validation.constraints.NotBlank;

/**
 * 摄取提交请求——REST 端点 {@code POST /api/knowledge/ingest/submit} 的入参。
 */
public record IngestionSubmitRequest(

        /** 待摄入文件（或目录）路径，必填 */
        @NotBlank(message = "filePath 不能为空")
        String filePath,

        /** 租户 ID，空串表示公开 */
        String tenantId,

        /** 文档版本，默认 v1 */
        String version) {

    public IngestionSubmitRequest {
        if (tenantId == null) {
            tenantId = "";
        }
        if (version == null || version.isBlank()) {
            version = "v1";
        }
    }
}
