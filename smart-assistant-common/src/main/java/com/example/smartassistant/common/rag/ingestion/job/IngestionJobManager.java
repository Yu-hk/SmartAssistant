/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

import com.example.smartassistant.common.rag.ingestion.IngestionResult;
import com.example.smartassistant.common.rag.ingestion.KnowledgeIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * 异步摄取任务管理器——将同步的 {@link KnowledgeIngestionService#parseAndIngest} 封装为
 * 带状态机的异步任务管线（对标生产级 RAG「异步可追踪数据链路」）。
 *
 * <p>职责：</p>
 * <ul>
 *     <li><b>异步执行</b>：提交后立即返回 jobId（202 语义），重活放到虚拟线程执行，
 *         不阻塞调用方请求链路；</li>
 *     <li><b>状态机驱动</b>：通过 {@code parseAndIngest} 的阶段回调把任务推进
 *         {@code UPLOADED→PARSING→CHUNKING→EMBEDDING→INDEXED}，异常进入 {@code FAILED}；</li>
 *     <li><b>提交去重</b>：同一（源路径 + 租户）的活跃任务直接复用，避免重复摄入；</li>
 *     <li><b>失败重试</b>：{@link #retry} 仅对 {@code FAILED} 任务生效，经 {@code RETRYING} 重跑；</li>
 *     <li><b>进度可查</b>：{@link #getJob}/{@link #awaitJob} 供进度轮询。</li>
 * </ul>
 *
 * <p>默认使用 JDK21 虚拟线程执行器（与项目 common 模块约定一致），并在销毁时优雅关闭。</p>
 */
public class IngestionJobManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(IngestionJobManager.class);

    private final KnowledgeIngestionService ingestion;
    private final IngestionJobRepository repo;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public IngestionJobManager(KnowledgeIngestionService ingestion, IngestionJobRepository repo) {
        this(ingestion, repo, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    public IngestionJobManager(KnowledgeIngestionService ingestion,
                              IngestionJobRepository repo,
                              ExecutorService executor,
                              boolean ownsExecutor) {
        this.ingestion = ingestion;
        this.repo = repo;
        this.executor = executor != null ? executor : Executors.newVirtualThreadPerTaskExecutor();
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * 提交一个摄取任务。
     *
     * @return 任务实体（已落库，状态 UPLOADED）；若同源已有活跃任务则直接返回该任务
     */
    public IngestionJob submit(String filePath, String tenantId, String version) {
        if (tenantId == null) {
            tenantId = "";
        }
        if (version == null || version.isBlank()) {
            version = "v1";
        }
        IngestionJob existing = repo.findActiveBySource(filePath, tenantId);
        if (existing != null) {
            log.info("[IngestionJob] 提交去重：相同源已有活跃任务 {}", existing.jobId());
            return existing;
        }
        IngestionJob job = IngestionJob.create(filePath, tenantId, version);
        repo.save(job);
        log.info("[IngestionJob] 提交摄取任务 {}: path={}, tenant={}, version={}",
                job.jobId(), filePath, tenantId, version);
        executor.submit(() -> safeRun(job.jobId()));
        return job;
    }

    private void safeRun(String jobId) {
        try {
            runJob(jobId);
        } catch (Exception e) {
            log.error("[IngestionJob] 任务执行异常 {}", jobId, e);
        }
    }

    /** 同步执行单个任务（带状态机推进与异常兜底）。 */
    void runJob(String jobId) {
        IngestionJob job = repo.get(jobId).orElse(null);
        if (job == null) {
            return;
        }
        try {
            repo.update(jobId, j -> j.withStatus(IngestionJobStatus.PARSING));
            IngestionResult result = ingestion.parseAndIngest(
                    job.sourcePath(), job.tenantId(), job.version(),
                    stage -> repo.update(jobId, j -> j.withStatus(stage)));
            if (result.isFailed()) {
                repo.update(jobId, j -> j.withStatus(IngestionJobStatus.FAILED)
                        .withErrorMessage(firstError(result)));
            } else {
                repo.update(jobId, j -> j.withStatus(IngestionJobStatus.INDEXED)
                        .withDocCount(result.docCount()));
            }
        } catch (Exception e) {
            log.warn("[IngestionJob] 任务失败 {}: {}", jobId, e.getMessage());
            repo.update(jobId, j -> j.withStatus(IngestionJobStatus.FAILED)
                    .withErrorMessage(e.getMessage()));
        } finally {
            repo.update(jobId, j -> j.withFinishedAt(System.currentTimeMillis()));
        }
    }

    /**
     * 重试一个失败的任务。
     *
     * @throws IllegalArgumentException 任务不存在
     * @throws IllegalStateException     任务非失败态（不可重试）
     */
    public IngestionJob retry(String jobId) {
        IngestionJob job = repo.get(jobId).orElseThrow(
                () -> new IllegalArgumentException("摄取任务不存在: " + jobId));
        if (!job.status().isFailed()) {
            throw new IllegalStateException("仅失败任务可重试，当前状态: " + job.status());
        }
        repo.update(jobId, j -> j.withStatus(IngestionJobStatus.RETRYING)
                .withRetryCount(j.retryCount() + 1)
                .withErrorMessage(null));
        executor.submit(() -> safeRun(jobId));
        return repo.get(jobId).orElse(job);
    }

    public Optional<IngestionJob> getJob(String jobId) {
        return repo.get(jobId);
    }

    public List<IngestionJob> listJobs(String tenantId) {
        return repo.listByTenant(tenantId);
    }

    /**
     * 阻塞等待任务到达终态（供测试或需要同步结果的调用方）。
     */
    public IngestionJob awaitJob(String jobId, Duration timeout)
            throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            IngestionJob j = repo.get(jobId).orElse(null);
            if (j != null && j.status().isTerminal()) {
                return j;
            }
            Thread.sleep(20);
        }
        throw new TimeoutException("等待摄取任务超时: " + jobId);
    }

    private static String firstError(IngestionResult result) {
        return (result.errors() != null && !result.errors().isEmpty())
                ? result.errors().get(0) : "未知错误";
    }

    @Override
    public void destroy() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }
}
