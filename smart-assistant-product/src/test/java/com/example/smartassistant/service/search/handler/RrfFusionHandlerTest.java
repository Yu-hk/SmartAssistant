package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.pipeline.AdaptiveWeightHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionHandlerTest {

    private static RrfFusionHandler handler() {
        RrfFusionHandler handler = new RrfFusionHandler();
        ReflectionTestUtils.setField(handler, "candidatePoolK", 20);
        ReflectionTestUtils.setField(handler, "qualityThreshold", 0.30);
        return handler;
    }

    @Test
    void emptyRetrieversDoNotDiluteExactMatchQuality() {
        RagSearchContext context = new RagSearchContext("MACBOOK-AIR-M3");
        context.addPathResult("精确匹配", List.of("MacBook Air M3\n价格：8999 元"));
        context.addPathResult("关键词搜索", List.of());
        context.addPathResult("BM25", List.of());
        context.addPathResult("知识库", List.of());

        handler().handle(context);

        assertThat(context.getFusedResults()).hasSize(1);
        assertThat(context.getQualityScore()).isEqualTo(1.0);
        assertThat(context.getQualityScore()).isGreaterThanOrEqualTo(context.getQualityThreshold());
    }

    @Test
    void adaptiveWeightsChangeCandidateOrderWithoutDownweightingExactCatalogFacts() {
        RagSearchContext context = new RagSearchContext("AirPods Pro 2 价格");
        new AdaptiveWeightHandler().handle(context);
        context.addPathResult("知识库", List.of("dense result"));
        context.addPathResult("BM25", List.of("sparse result"));
        context.addPathResult("精确匹配", List.of("exact catalog result"));

        handler().handle(context);

        assertThat(context.getFusedResults().getFirst().getContent()).isEqualTo("sparse result");
        assertThat(context.getFusedResults().get(1).getContent()).isEqualTo("exact catalog result");
        assertThat(context.getFusedResults().get(2).getContent()).isEqualTo("dense result");
        assertThat(context.getQualityScore()).isBetween(0.0, 1.0);
    }

    @Test
    void missingAdaptiveWeightsKeepLegacyRrfScores() {
        RagSearchContext context = new RagSearchContext("商品咨询");
        context.addPathResult("BM25", List.of("sparse result"));
        context.addPathResult("知识库", List.of("dense result"));

        handler().handle(context);

        assertThat(context.getFusedResults()).allSatisfy(item ->
                assertThat(item.getRrfScore()).isEqualTo(1.0 / 61));
    }
}
