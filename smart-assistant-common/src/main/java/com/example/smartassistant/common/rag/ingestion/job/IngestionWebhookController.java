/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

import com.example.smartassistant.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 摄入 Webhook 端点（REQ-1 触发机制之「对象存储事件 → 提交」）。
 * <p>
 * 接收对象存储（如 OSS/COS/MinIO）的事件回调，将其转化为一次异步摄取任务提交。
 * 仅提取 {@code filePath / tenantId / version} 三个字段，对具体云厂商事件结构保持宽松（缺省兜底）。
 * </p>
 *
 * <p>典型事件体（兼容常见对象存储回调）：</p>
 * <pre>{@code
 * {
 *   "event": "ObjectCreated:Put",
 *   "filePath": "/data/inbox/refund-policy-v2.pdf",
 *   "tenantId": "tenant_001",
 *   "version": "v2"
 * }
 * }</pre>
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/knowledge/ingest/webhook")
public class IngestionWebhookController {

    private static final Logger log = LoggerFactory.getLogger(IngestionWebhookController.class);

    private final IngestionJobManager manager;

    public IngestionWebhookController(IngestionJobManager manager) {
        this.manager = manager;
    }

    /**
     * 接收对象存储事件回调，提交摄取任务。
     * <p>无论事件类型，均尝试提取文件路径并提交（由管理器做同源去重）。</p>
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> onEvent(@RequestBody Map<String, Object> payload) {
        String filePath = asString(payload, "filePath", asString(payload, "key", asString(payload, "object", "")));
        if (filePath == null || filePath.isBlank()) {
            return ApiResponse.error(400, "Webhook 事件缺少 filePath/key/object 字段");
        }
        String tenantId = asString(payload, "tenantId", "");
        String version = asString(payload, "version", "v1");
        try {
            IngestionJob job = manager.submit(filePath, tenantId, version);
            Map<String, Object> body = Map.of(
                    "jobId", job.jobId(),
                    "status", job.status().name(),
                    "filePath", filePath);
            log.info("[IngestWebhook] 事件触发摄入: jobId={}, path={}, tenant={}",
                    job.jobId(), filePath, tenantId);
            return ApiResponse.success(body);
        } catch (Exception e) {
            log.warn("[IngestWebhook] 提交失败: {}", e.getMessage());
            return ApiResponse.error(500, "提交摄取任务失败: " + e.getMessage());
        }
    }

    private static String asString(Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        String s = v.toString().trim();
        return s.isBlank() ? fallback : s;
    }
}
