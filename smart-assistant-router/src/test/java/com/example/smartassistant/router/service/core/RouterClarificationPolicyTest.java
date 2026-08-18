package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterClarificationPolicyTest {

    @Test
    void clarificationOnlyShortCircuitsSingleIntentWithoutExecutableNode() {
        TaskAnalysisResult singleIntent = new TaskAnalysisResult();
        singleIntent.setIntentCategory("ORDER");
        singleIntent.setNeedsClarification(true);
        singleIntent.setClarificationQuestions(List.of("请提供订单号"));
        assertTrue(RouterService.shouldShortCircuitForClarification(singleIntent));

        TaskAnalysisResult multiIntent = new TaskAnalysisResult();
        multiIntent.setIntentCategory("COMPLEX");
        multiIntent.setNeedsClarification(true);
        multiIntent.setClarificationQuestions(List.of("请选择商品"));
        multiIntent.setSubIntents(List.of(
                Map.of("intent", "PRODUCT_QUERY"),
                Map.of("intent", "CREATE_ORDER")));
        assertFalse(RouterService.shouldShortCircuitForClarification(multiIntent));
    }
}
