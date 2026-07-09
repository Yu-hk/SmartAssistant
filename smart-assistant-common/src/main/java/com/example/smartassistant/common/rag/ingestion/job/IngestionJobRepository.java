/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 摄取任务存储接口——解耦任务状态持久化方式（内存 / JDBC / Redis 可替换）。
 *
 * <p>默认实现 {@link InMemoryIngestionJobRepository} 用于单实例；生产可替换为
 * 基于 {@code JdbcTemplate} 或 Redis 的实现以支持跨实例与重启存活。</p>
 */
public interface IngestionJobRepository {

    /** 保存（新增或覆盖）任务。 */
    IngestionJob save(IngestionJob job);

    /** 原子更新：以当前任务为入参执行变换并返回新实例。 */
    IngestionJob update(String jobId, UnaryOperator<IngestionJob> mutator);

    /** 按 jobId 获取任务。 */
    Optional<IngestionJob> get(String jobId);

    /** 列出某租户下的全部任务。 */
    List<IngestionJob> listByTenant(String tenantId);

    /** 删除任务。 */
    void delete(String jobId);

    /**
     * 查找同一源（路径 + 租户）尚未到达终态的任务——用于提交去重。
     *
     * @return 活跃任务；不存在则返回 {@code null}
     */
    IngestionJob findActiveBySource(String sourcePath, String tenantId);
}
