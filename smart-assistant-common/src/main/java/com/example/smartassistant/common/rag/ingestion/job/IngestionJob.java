/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

import java.util.UUID;

/**
 * 摄取任务实体——承载状态机当前态、进度、错误信息，采用不可变设计（{@code withX} 返回新实例）。
 *
 * <p>由 {@link IngestionJobManager} 在异步执行过程中持续更新，并通过
 * {@link IngestionJobRepository} 持久化以支持「进度 API + 跨重启可追踪」。</p>
 */
public final class IngestionJob {

    private final String jobId;
    private final String sourcePath;
    private final String sourceName;
    private final String tenantId;
    private final String version;
    private final IngestionJobStatus status;
    private final String stageNote;
    private final int progress;
    private final int docCount;
    private final String errorMessage;
    private final String contentHash;
    private final long createdAt;
    private final long updatedAt;
    private final long finishedAt;
    private final int retryCount;

    private IngestionJob(String jobId, String sourcePath, String sourceName, String tenantId,
                         String version, IngestionJobStatus status, String stageNote, int progress,
                         int docCount, String errorMessage, String contentHash, long createdAt,
                         long updatedAt, long finishedAt, int retryCount) {
        this.jobId = jobId;
        this.sourcePath = sourcePath;
        this.sourceName = sourceName;
        this.tenantId = tenantId;
        this.version = version;
        this.status = status;
        this.stageNote = stageNote;
        this.progress = progress;
        this.docCount = docCount;
        this.errorMessage = errorMessage;
        this.contentHash = contentHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.finishedAt = finishedAt;
        this.retryCount = retryCount;
    }

    /** 创建初始任务（状态 UPLOADED，进度 5%）。 */
    public static IngestionJob create(String sourcePath, String tenantId, String version) {
        long now = System.currentTimeMillis();
        String name = extractName(sourcePath);
        return new IngestionJob(
                "ing-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                sourcePath, name,
                tenantId == null ? "" : tenantId,
                (version == null || version.isBlank()) ? "v1" : version,
                IngestionJobStatus.UPLOADED,
                "任务已创建",
                IngestionJobStatus.UPLOADED.progress(),
                0, null, null,
                now, now, 0, 0);
    }

    private static String extractName(String sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty()) {
            return "";
        }
        int idx = Math.max(sourcePath.lastIndexOf('/'), sourcePath.lastIndexOf('\\'));
        return idx >= 0 ? sourcePath.substring(idx + 1) : sourcePath;
    }

    // ===================== 不可变 copy 方法 =====================

    public IngestionJob withStatus(IngestionJobStatus status) {
        int p = (status == IngestionJobStatus.FAILED || status == IngestionJobStatus.RETRYING)
                ? this.progress : status.progress();
        return new IngestionJob(jobId, sourcePath, sourceName, tenantId, version, status, stageNote,
                p, docCount, errorMessage, contentHash, createdAt, System.currentTimeMillis(),
                finishedAt, retryCount);
    }

    public IngestionJob withProgress(int progress) {
        return new IngestionJob(jobId, sourcePath, sourceName, tenantId, version, status, stageNote,
                progress, docCount, errorMessage, contentHash, createdAt, System.currentTimeMillis(),
                finishedAt, retryCount);
    }

    public IngestionJob withStageNote(String stageNote) {
        return new IngestionJob(jobId, sourcePath, sourceName, tenantId, version, status, stageNote,
                progress, docCount, errorMessage, contentHash, createdAt, System.currentTimeMillis(),
                finishedAt, retryCount);
    }

    public IngestionJob withDocCount(int docCount) {
        return new IngestionJob(jobId, sourcePath, sourceName, tenantId, version, status, stageNote,
                progress, docCount, errorMessage, contentHash, createdAt, System.currentTimeMillis(),
                finishedAt, retryCount);
    }

    public IngestionJob withErrorMessage(String errorMessage) {
        return new IngestionJob(jobId, sourcePath, sourceName, tenantId, version, status, stageNote,
                progress, docCount, errorMessage, contentHash, createdAt, System.currentTimeMillis(),
                finishedAt, retryCount);
    }

    public IngestionJob withFinishedAt(long finishedAt) {
        return new IngestionJob(jobId, sourcePath, sourceName, tenantId, version, status, stageNote,
                progress, docCount, errorMessage, contentHash, createdAt, System.currentTimeMillis(),
                finishedAt, retryCount);
    }

    public IngestionJob withRetryCount(int retryCount) {
        return new IngestionJob(jobId, sourcePath, sourceName, tenantId, version, status, stageNote,
                progress, docCount, errorMessage, contentHash, createdAt, System.currentTimeMillis(),
                finishedAt, retryCount);
    }

    /** 投影为对外视图 DTO（供进度 API 返回）。 */
    public IngestionJobView toView() {
        return new IngestionJobView(jobId, sourceName, tenantId, status.name(), status.label(),
                progress, docCount, errorMessage, stageNote, retryCount, createdAt, updatedAt, finishedAt);
    }

    // ===================== Getter =====================

    public String jobId() { return jobId; }
    public String sourcePath() { return sourcePath; }
    public String sourceName() { return sourceName; }
    public String tenantId() { return tenantId; }
    public String version() { return version; }
    public IngestionJobStatus status() { return status; }
    public String stageNote() { return stageNote; }
    public int progress() { return progress; }
    public int docCount() { return docCount; }
    public String errorMessage() { return errorMessage; }
    public String contentHash() { return contentHash; }
    public long createdAt() { return createdAt; }
    public long updatedAt() { return updatedAt; }
    public long finishedAt() { return finishedAt; }
    public int retryCount() { return retryCount; }
}
