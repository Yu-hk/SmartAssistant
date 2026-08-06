package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RouterSingleIntentFastPathTest {

    @Test
    void mapsClearSingleDomainIntents() {
        assertEquals("order", RouterService.resolveSingleIntentAgent(analysis("ORDER")));
        assertEquals("product", RouterService.resolveSingleIntentAgent(analysis("PRODUCT")));
        assertEquals("general", RouterService.resolveSingleIntentAgent(analysis("GENERAL")));
    }

    @Test
    void keepsComplexOrAmbiguousTasksOnCollaborativePath() {
        TaskAnalysisResult multiIntent = analysis("PRODUCT");
        multiIntent.setSubIntents(List.of(
                Map.of("intent", "PRODUCT"),
                Map.of("intent", "ORDER")));
        assertNull(RouterService.resolveSingleIntentAgent(multiIntent));

        TaskAnalysisResult clarification = analysis("ORDER");
        clarification.setNeedsClarification(true);
        assertNull(RouterService.resolveSingleIntentAgent(clarification));

        assertNull(RouterService.resolveSingleIntentAgent(analysis("COMPLEX")));
    }

    private static TaskAnalysisResult analysis(String category) {
        TaskAnalysisResult result = new TaskAnalysisResult();
        result.setIntentCategory(category);
        result.setConfidence(0.9);
        return result;
    }
}
