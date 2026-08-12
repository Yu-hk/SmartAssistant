package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterSingleIntentFastPathTest {

    @Test
    void mapsClearSingleDomainIntents() {
        assertEquals("order", RouterService.resolveSingleIntentAgent(analysis("ORDER")));
        assertEquals("product", RouterService.resolveSingleIntentAgent(analysis("PRODUCT")));
        assertEquals("general", RouterService.resolveSingleIntentAgent(analysis("GENERAL")));
        assertEquals("order", RouterService.resolveSingleIntentAgent(analysis("退款与售后政策")));
        assertEquals("order", RouterService.resolveSingleIntentAgent(analysis("REFUND_POLICY")));
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

    @Test
    void clarificationOnlyShortCircuitsSingleIntent() {
        TaskAnalysisResult singleIntent = analysis("ORDER");
        singleIntent.setNeedsClarification(true);
        singleIntent.setClarificationQuestions(List.of("请提供订单号"));
        assertTrue(RouterService.shouldShortCircuitForClarification(singleIntent));

        TaskAnalysisResult multiIntent = analysis("PRODUCT");
        multiIntent.setNeedsClarification(true);
        multiIntent.setClarificationQuestions(List.of("请选择商品"));
        multiIntent.setSubIntents(List.of(
                Map.of("intent", "PRODUCT_QUERY"),
                Map.of("intent", "CREATE_ORDER")));
        assertFalse(RouterService.shouldShortCircuitForClarification(multiIntent));
    }

    @Test
    void deterministicallyAnalyzesPopularProductThenOrderRequest() {
        TaskAnalysisResult analysis = RouterService.buildProductOrderMultiIntentAnalysis(
                "帮我查下热门商品，然后下单");

        assertNotNull(analysis);
        assertEquals("COMPLEX", analysis.getIntentCategory());
        assertEquals(2, analysis.getSubIntents().size());
        assertTrue(analysis.isNeedsClarification());
        assertTrue(analysis.getMissingSlots().contains("shippingAddress"));
        assertFalse(RouterService.shouldShortCircuitForClarification(analysis));
    }

    @Test
    void deterministicAnalysisPreservesExplicitReadOnlyConstraints() {
        TaskAnalysisResult analysis = RouterService.buildProductOrderMultiIntentAnalysis(
                "查询热门商品并说明下单资料；本次只查询，禁止创建订单、支付、退款或取消");

        assertNotNull(analysis);
        assertTrue(analysis.getActionConstraints().contains("仅查询和说明，不创建订单"));
        assertTrue(analysis.getActionConstraints().contains("不执行支付"));
        assertTrue(analysis.getActionConstraints().contains("不执行退款"));
        assertTrue(analysis.getActionConstraints().contains("不执行取消"));
        assertNull(RouterService.buildProductOrderMultiIntentAnalysis("查询热门商品"));
    }


    @Test
    void existingOrderQueryIsNotRewrittenAsOrderPreparation() {
        assertNull(RouterService.buildProductOrderMultiIntentAnalysis(
                "查询一下我最近的订单状态，同时推荐三款热门商品；"
                        + "只允许查询，不要取消、退款、支付或创建订单"));
    }

    @Test
    void negatedOrderActionAloneDoesNotTriggerOrderPreparation() {
        assertNull(RouterService.buildProductOrderMultiIntentAnalysis(
                "查询热门商品，本次只查询，不购买，也不要创建订单"));
    }

    private static TaskAnalysisResult analysis(String category) {
        TaskAnalysisResult result = new TaskAnalysisResult();
        result.setIntentCategory(category);
        result.setConfidence(0.9);
        return result;
    }
}
