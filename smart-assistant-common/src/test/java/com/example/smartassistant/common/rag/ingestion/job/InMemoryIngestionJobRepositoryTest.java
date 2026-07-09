/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存版任务存储——增改查、终态去重、缺失更新返回 null。
 */
class InMemoryIngestionJobRepositoryTest {

    @Test
    void saveGetUpdateListDelete() {
        InMemoryIngestionJobRepository repo = new InMemoryIngestionJobRepository();
        IngestionJob job = IngestionJob.create("/a.txt", "t1", "v1");
        repo.save(job);
        assertEquals(job.jobId(), repo.get(job.jobId()).orElseThrow().jobId());

        IngestionJob updated = repo.update(job.jobId(), j -> j.withStatus(IngestionJobStatus.PARSING));
        assertEquals(IngestionJobStatus.PARSING, updated.status());
        assertEquals(1, repo.listByTenant("t1").size());

        // 活跃任务可被去重命中
        assertEquals(job.jobId(), repo.findActiveBySource("/a.txt", "t1").jobId());

        // 标记终态后不再被去重命中
        repo.update(job.jobId(), j -> j.withStatus(IngestionJobStatus.INDEXED).withDocCount(2));
        assertNull(repo.findActiveBySource("/a.txt", "t1"));

        repo.delete(job.jobId());
        assertTrue(repo.get(job.jobId()).isEmpty());
    }

    @Test
    void updateMissingReturnsNull() {
        InMemoryIngestionJobRepository repo = new InMemoryIngestionJobRepository();
        assertNull(repo.update("missing", j -> j));
        assertThrows(NullPointerException.class, () -> repo.update("missing", j -> j).jobId());
    }
}
