/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

import com.example.smartassistant.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 摄取任务 REST 端点——提供「提交即受理 + 进度轮询 + 失败重试」的生产级契约。
 *
 * <p>仅在 Web 应用上下文（{@code @ConditionalOnWebApplication}）下激活，
 * 由 {@link IngestionJobAutoConfiguration} 自动装配。</p>
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/knowledge/ingest")
public class IngestionJobController {

    private final IngestionJobManager manager;

    public IngestionJobController(IngestionJobManager manager) {
        this.manager = manager;
    }

    /**
     * 提交摄取任务（202 语义：立即返回 jobId，后台异步执行）。
     */
    @PostMapping("/submit")
    public ApiResponse<JobSubmitResponse> submit(@Valid @RequestBody IngestionSubmitRequest request) {
        IngestionJob job = manager.submit(request.filePath(), request.tenantId(), request.version());
        return ApiResponse.success(new JobSubmitResponse(
                job.jobId(), job.status().name(), job.status().label(), true));
    }

    /**
     * 查询任务进度（轮询端点）。
     */
    @GetMapping("/jobs/{jobId}")
    public ApiResponse<IngestionJobView> get(@PathVariable String jobId) {
        return manager.getJob(jobId)
                .map(j -> ApiResponse.success(j.toView()))
                .orElse(ApiResponse.error(404, "摄取任务不存在: " + jobId));
    }

    /**
     * 重试失败任务。
     */
    @PostMapping("/jobs/{jobId}/retry")
    public ApiResponse<IngestionJobView> retry(@PathVariable String jobId) {
        try {
            return ApiResponse.success(manager.retry(jobId).toView());
        } catch (IllegalStateException e) {
            return ApiResponse.error(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    /**
     * 列出某租户的任务。
     */
    @GetMapping("/jobs")
    public ApiResponse<List<IngestionJobView>> list(
            @RequestParam(required = false, defaultValue = "") String tenantId) {
        return ApiResponse.success(
                manager.listJobs(tenantId).stream().map(IngestionJob::toView).toList());
    }
}
