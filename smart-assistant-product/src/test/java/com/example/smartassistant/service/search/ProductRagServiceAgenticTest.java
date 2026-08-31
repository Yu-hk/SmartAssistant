/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.pipeline.RagSearchPipeline;
import com.example.smartassistant.config.NativeRagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProductRagServiceAgenticTest {

    @Test
    void insufficientFirstAttemptShouldRunOneSupplementalRetrievalAndExposeEvidence() {
        RagSearchPipeline pipeline = mock(RagSearchPipeline.class);
        AtomicInteger calls = new AtomicInteger();
        when(pipeline.execute(any(RagSearchContext.class))).thenAnswer(invocation -> {
            RagSearchContext context = invocation.getArgument(0);
            if (calls.incrementAndGet() == 1) {
                context.setQualityScore(0.0);
                context.setFusedResults(List.of());
            } else {
                context.setQualityScore(0.82);
                context.setFusedResults(List.of(new RagSearchContext.RankedItem(
                        "【价格政策】[CID:PROD-PRICE-001]\n以下单时价格为准。", 0.82)));
            }
            return context;
        });
        SupplementalQueryPlanner planner = mock(SupplementalQueryPlanner.class);
        when(planner.plan(anyString(), anyString(), anyString(), eq(2)))
                .thenReturn("商品官方价格政策");
        NativeRagProperties properties = properties(2, 0.5);
        KnowledgeScopeSelector selector = (agent, skills, query) ->
                new KnowledgeScopeSelector.KnowledgeScope(List.of("product_knowledge"), "test");

        ProductRagService service = new ProductRagService(pipeline, selector, planner, properties);
        RetrievalQualityResult result = service.retrieveWithQualityResult("商品价格怎么确定");

        assertTrue(result.isHighQuality());
        assertTrue(result.getContent().contains("知识域：product_knowledge；检索轮次：2"));
        assertTrue(result.getContent().contains("[E1]"));
        assertTrue(result.getContent().contains("[CID:PROD-PRICE-001]"));
        verify(pipeline, times(2)).execute(any(RagSearchContext.class));
        verify(planner).plan(eq("商品价格怎么确定"), eq("商品价格怎么确定"),
                contains("未召回任何证据"), eq(2));
    }

    @Test
    void sufficientFirstAttemptShouldNotCallSupplementalPlanner() {
        RagSearchPipeline pipeline = mock(RagSearchPipeline.class);
        when(pipeline.execute(any(RagSearchContext.class))).thenAnswer(invocation -> {
            RagSearchContext context = invocation.getArgument(0);
            assertEquals(List.of("product_knowledge"), context.getAttribute("rag.knowledgeBases"));
            context.setQualityScore(0.91);
            context.setFusedResults(List.of(
                    new RagSearchContext.RankedItem("已核验商品信息", 0.91)));
            return context;
        });
        SupplementalQueryPlanner planner = mock(SupplementalQueryPlanner.class);
        ProductRagService service = new ProductRagService(
                pipeline,
                (agent, skills, query) -> new KnowledgeScopeSelector.KnowledgeScope(
                        List.of("product_knowledge"), "test"),
                planner,
                properties(2, 0.5));

        RetrievalQualityResult result = service.retrieveWithQualityResult("查询商品");

        assertTrue(result.isHighQuality());
        assertTrue(result.getContent().contains("检索轮次：1"));
        verify(pipeline, times(1)).execute(any(RagSearchContext.class));
        verifyNoInteractions(planner);
    }

    @Test
    void duplicateSupplementalQueryShouldStopLoop() {
        RagSearchPipeline pipeline = mock(RagSearchPipeline.class);
        when(pipeline.execute(any(RagSearchContext.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SupplementalQueryPlanner planner = (original, previous, summary, attempt) -> previous;
        ProductRagService service = new ProductRagService(
                pipeline,
                (agent, skills, query) -> new KnowledgeScopeSelector.KnowledgeScope(
                        List.of("product_knowledge"), "test"),
                planner,
                properties(3, 0.5));

        RetrievalQualityResult result = service.retrieveWithQualityResult("未知商品规则");

        assertTrue(result.isRejected());
        verify(pipeline, times(1)).execute(any(RagSearchContext.class));
    }

    private static NativeRagProperties properties(int attempts, double sufficientScore) {
        NativeRagProperties properties = new NativeRagProperties();
        properties.setEnabled(true);
        properties.setMaxAttempts(attempts);
        properties.setMaxEvidenceItems(8);
        properties.setSufficientScore(sufficientScore);
        return properties;
    }
}
