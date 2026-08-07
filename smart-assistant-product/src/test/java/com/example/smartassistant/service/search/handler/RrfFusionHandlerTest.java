package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionHandlerTest {

    @Test
    void emptyRetrieversDoNotDiluteExactMatchQuality() {
        RagSearchContext context = new RagSearchContext("MACBOOK-AIR-M3");
        context.addPathResult("精确匹配", List.of("MacBook Air M3\n价格：8999 元"));
        context.addPathResult("关键词搜索", List.of());
        context.addPathResult("BM25", List.of());
        context.addPathResult("知识库", List.of());

        RrfFusionHandler handler = new RrfFusionHandler();
        ReflectionTestUtils.setField(handler, "candidatePoolK", 20);
        ReflectionTestUtils.setField(handler, "qualityThreshold", 0.30);
        handler.handle(context);

        assertThat(context.getFusedResults()).hasSize(1);
        assertThat(context.getQualityScore()).isEqualTo(1.0);
        assertThat(context.getQualityScore()).isGreaterThanOrEqualTo(context.getQualityThreshold());
    }
}
