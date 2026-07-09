/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

import com.example.smartassistant.common.rag.ingestion.IngestionResult;
import com.example.smartassistant.common.rag.ingestion.KnowledgeIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 摄取任务 REST 端点——采用 standalone MockMvc（不加载完整 Spring 上下文）。
 *
 * <p>注：{@code @Valid} 入参校验依赖 Jakarta Bean Validation 实现（运行期由
 * spring-boot-starter-web 提供），standalone MockMvc 不自动装配，故此处不覆盖校验路径。</p>
 */
class IngestionJobControllerTest {

    private final KnowledgeIngestionService ingestion = mock(KnowledgeIngestionService.class);
    private final IngestionJobManager mgr = new IngestionJobManager(ingestion, new InMemoryIngestionJobRepository());
    private final IngestionJobController controller = new IngestionJobController(mgr);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
    private final ObjectMapper om = new ObjectMapper();

    private void stubSuccess() {
        when(ingestion.parseAndIngest(anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    Consumer<IngestionJobStatus> l = inv.getArgument(3);
                    l.accept(IngestionJobStatus.PARSING);
                    l.accept(IngestionJobStatus.CHUNKING);
                    l.accept(IngestionJobStatus.EMBEDDING);
                    return IngestionResult.success(1, 5);
                });
    }

    @Test
    void submitReturnsJobIdAndAccepted() throws Exception {
        stubSuccess();
        mvc.perform(post("/api/knowledge/ingest/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new IngestionSubmitRequest("/x.txt", "t1", "v1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobId").exists())
                .andExpect(jsonPath("$.data.accepted").value(true));
        mgr.destroy();
    }

    @Test
    void getJobReturnsView() throws Exception {
        stubSuccess();
        IngestionJob job = mgr.submit("/y.txt", "t1", "v1");
        mvc.perform(get("/api/knowledge/ingest/jobs/" + job.jobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(job.jobId()))
                .andExpect(jsonPath("$.data.status").exists());
        mgr.destroy();
    }

    @Test
    void retryUnknownReturns404() throws Exception {
        mvc.perform(post("/api/knowledge/ingest/jobs/nope/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
        mgr.destroy();
    }
}
