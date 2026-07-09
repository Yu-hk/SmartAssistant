/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * 内存版摄取任务存储——单实例默认实现，进程重启即丢失。
 *
 * <p>与项目 RAG 知识库的内存范式一致；生产可替换为 JDBC/Redis 实现。</p>
 */
public class InMemoryIngestionJobRepository implements IngestionJobRepository {

    private final ConcurrentHashMap<String, IngestionJob> store = new ConcurrentHashMap<>();

    @Override
    public IngestionJob save(IngestionJob job) {
        store.put(job.jobId(), job);
        return job;
    }

    @Override
    public IngestionJob update(String jobId, UnaryOperator<IngestionJob> mutator) {
        IngestionJob current = store.get(jobId);
        if (current == null) {
            return null;
        }
        IngestionJob next = mutator.apply(current);
        store.put(jobId, next);
        return next;
    }

    @Override
    public Optional<IngestionJob> get(String jobId) {
        return Optional.ofNullable(store.get(jobId));
    }

    @Override
    public List<IngestionJob> listByTenant(String tenantId) {
        return store.values().stream()
                .filter(j -> j.tenantId().equals(tenantId))
                .toList();
    }

    @Override
    public void delete(String jobId) {
        store.remove(jobId);
    }

    @Override
    public IngestionJob findActiveBySource(String sourcePath, String tenantId) {
        return store.values().stream()
                .filter(j -> !j.status().isTerminal()
                        && j.sourcePath().equals(sourcePath)
                        && j.tenantId().equals(tenantId))
                .findFirst()
                .orElse(null);
    }
}
