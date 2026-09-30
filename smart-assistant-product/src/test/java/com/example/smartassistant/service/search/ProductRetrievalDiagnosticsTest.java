package com.example.smartassistant.service.search;

import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.rag.pipeline.*;
import com.example.smartassistant.service.core.ProductFactQueryService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductRetrievalDiagnosticsTest {
    private static AgentExecutionResponse facts(boolean complete, boolean known) {
        return AgentExecutionResponse.success("合成只读回答", Map.of("handled", true,
                "fieldCoverage", Map.of("complete", complete), "productEvidence", List.of(
                Map.of("productCode", "A", "productName", "甲", "quantity", 1, "status", "RESOLVED",
                        "fields", Map.of("WEIGHT", Map.of("known", known, "evidence", known ? "20克" : ""))))),
                DomainQualityResult.pass(1));
    }
    @Test void unknownContextIsExplicitAndNeverInventsNumericValue() {
        var result = ProductRetrievalDiagnostics.catalog(facts(true, false), 8);
        assertTrue(result.isHighQuality()); assertTrue(result.getContent().contains("known=false"));
        assertEquals("catalog_field_context", result.getDiagnostics().get("contextKind"));
    }
    @Test void incompleteCoverageCannotBeHighQuality() {
        assertTrue(ProductRetrievalDiagnostics.catalog(facts(false, true), 8).isRejected());
    }
    @Test void scopeBypassesFuzzyRankingAndSupplementalQueries() {
        var pipeline = mock(RagSearchPipeline.class);
        var service = new ProductRagService(pipeline, null);
        var catalog = mock(ProductFactQueryService.class);
        when(catalog.query("甲重量", List.of(), "test")).thenReturn(facts(true, true));
        service.setFactQueryService(catalog);
        assertTrue(service.retrieveWithQualityResult("甲重量", "test").isHighQuality());
        verifyNoInteractions(pipeline);
    }
    @Test void snapshotsAndFinalContextHaveStableHashesNotUserText() {
        var context = new RagSearchContext("private user text");
        context.addPathResult("BM25", List.of("catalog text"));
        context.setFusedResults(List.of(new RagSearchContext.RankedItem("catalog text", .01)));
        context.snapshot("fusion");
        context.setFusedResults(List.of());
        var trace = ProductRetrievalDiagnostics.pipeline("test", List.of(context), context, "", 8);
        assertFalse(trace.toString().contains("private user text"));
        assertFalse(trace.toString().contains("catalog text"));
        assertTrue(trace.toString().contains(RagSearchContext.evidenceId("catalog text")));
        assertEquals(1, ((List<?>) context.getStageSnapshots().getFirst().get("items")).size());
    }
}
