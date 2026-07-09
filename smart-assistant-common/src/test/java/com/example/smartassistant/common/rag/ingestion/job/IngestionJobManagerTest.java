/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

import com.example.smartassistant.common.rag.ingestion.IngestionResult;
import com.example.smartassistant.common.rag.ingestion.KnowledgeIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 异步摄取任务管理器——状态机流转、失败重试、去重、重试守卫。
 */
@ExtendWith(MockitoExtension.class)
class IngestionJobManagerTest {

    @Mock
    private KnowledgeIngestionService ingestion;

    @Test
    void submitRunsAsyncThroughStagesToIndexed() throws Exception {
        AtomicInteger stageCalls = new AtomicInteger();
        when(ingestion.parseAndIngest(anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    Consumer<IngestionJobStatus> l = inv.getArgument(3);
                    l.accept(IngestionJobStatus.PARSING);
                    stageCalls.incrementAndGet();
                    l.accept(IngestionJobStatus.CHUNKING);
                    stageCalls.incrementAndGet();
                    l.accept(IngestionJobStatus.EMBEDDING);
                    stageCalls.incrementAndGet();
                    return IngestionResult.success(3, 20);
                });

        IngestionJobManager mgr = new IngestionJobManager(ingestion, new InMemoryIngestionJobRepository());
        IngestionJob job = mgr.submit("/docs/a.txt", "t1", "v1");
        assertEquals(IngestionJobStatus.UPLOADED, job.status());

        IngestionJob done = mgr.awaitJob(job.jobId(), Duration.ofSeconds(5));
        assertEquals(IngestionJobStatus.INDEXED, done.status());
        assertEquals(100, done.progress());
        assertEquals(3, done.docCount());
        assertTrue(stageCalls.get() >= 3);
        mgr.destroy();
    }

    @Test
    void failureSetsFailedAndRetryRecovers() throws Exception {
        when(ingestion.parseAndIngest(anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(IngestionResult.success(2, 15));

        IngestionJobManager mgr = new IngestionJobManager(ingestion, new InMemoryIngestionJobRepository());
        IngestionJob job = mgr.submit("/docs/b.txt", "t1", "v1");

        IngestionJob failed = mgr.awaitJob(job.jobId(), Duration.ofSeconds(5));
        assertEquals(IngestionJobStatus.FAILED, failed.status());
        assertNotNull(failed.errorMessage());

        IngestionJob retried = mgr.retry(job.jobId());
        assertEquals(IngestionJobStatus.RETRYING, retried.status());

        IngestionJob ok = mgr.awaitJob(job.jobId(), Duration.ofSeconds(5));
        assertEquals(IngestionJobStatus.INDEXED, ok.status());
        assertEquals(1, ok.retryCount());
        mgr.destroy();
    }

    @Test
    void retryOnNonFailedOrMissingThrows() {
        IngestionJobManager mgr = new IngestionJobManager(ingestion, new InMemoryIngestionJobRepository());
        assertThrows(IllegalArgumentException.class, () -> mgr.retry("nope"));
        mgr.destroy();
    }

    @Test
    void duplicateSubmitReturnsExistingJob() throws Exception {
        when(ingestion.parseAndIngest(anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    Consumer<IngestionJobStatus> l = inv.getArgument(3);
                    l.accept(IngestionJobStatus.PARSING);
                    l.accept(IngestionJobStatus.CHUNKING);
                    l.accept(IngestionJobStatus.EMBEDDING);
                    return IngestionResult.success(1, 5);
                });

        IngestionJobManager mgr = new IngestionJobManager(ingestion, new InMemoryIngestionJobRepository());
        IngestionJob j1 = mgr.submit("/docs/c.txt", "t1", "v1");
        IngestionJob j2 = mgr.submit("/docs/c.txt", "t1", "v1");
        assertSame(j1, j2);

        mgr.awaitJob(j1.jobId(), Duration.ofSeconds(5));
        mgr.destroy();
    }
}
