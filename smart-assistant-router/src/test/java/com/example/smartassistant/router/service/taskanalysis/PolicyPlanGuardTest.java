package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PolicyPlanGuardTest {
    @Test void repairsThroughModelOnceAndRefusesRepeatedOffTopicPlan() {
        var model = org.mockito.Mockito.mock(com.example.smartassistant.router.service.core.ModelRoutingService.class);
        var stages = new com.example.smartassistant.router.service.prompt.RouterStageAwareService();
        var service = new TaskAnalysisService(model, null, null, stages, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxEntityEntries", 20);
        String wrong = "{\"intent_category\":\"product\",\"sub_intents\":[{\"operation\":\"DISCOVER_PRODUCTS\",\"description\":\"推荐办公耳机\"}]}";
        String right = "{\"intent_category\":\"product\",\"sub_intents\":[{\"operation\":\"QUERY_PRODUCT\",\"description\":\"检索退货政策\"}]}";
        var bad = new com.example.smartassistant.router.service.core.ModelRoutingService.IntentModelResponse(wrong, "test", "LIGHT", 20, 1);
        var good = new com.example.smartassistant.router.service.core.ModelRoutingService.IntentModelResponse(right, "test", "LIGHT", 20, 1);
        org.mockito.Mockito.when(model.callForIntent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(bad, good, bad, bad);
        String question = "七天无理由退货需要满足什么条件？";
        assertEquals("QUERY_PRODUCT", service.analyze(question).getSubIntents().getFirst().get("operation"));
        assertFalse(service.analyze(question).hasSubIntents());
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(4)).callForIntent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(question));
    }
    private TaskAnalysisResult plan(String operation, String description) {
        var result = new TaskAnalysisResult();
        result.setSubIntents(List.of(Map.of("operation", operation, "description", description, "access_mode", "READ")));
        return result;
    }
    @Test void rejectsShoppingPlanForPolicyOnlyQuestion() {
        String question = "七天无理由退货需要满足什么条件？请根据平台知识库说明。";
        assertTrue(PolicyPlanGuard.requiresRepair(question, plan("DISCOVER_PRODUCTS", "为办公室推荐耳机")));
        assertTrue(PolicyPlanGuard.requiresRepair(question, plan("QUERY_PRODUCT", "为办公室推荐耳机")));
        assertFalse(PolicyPlanGuard.requiresRepair(question, plan("QUERY_PRODUCT", "检索七天退货政策并说明条件")));
    }
    @Test void doesNotOverrideMixedOrOrderIntents() {
        for (String question : List.of("推荐耳机，并说明退货政策", "帮我退货，告诉我退款流程", "订单号ORD-123退款进度", "申请退款需要什么条件"))
            assertFalse(PolicyPlanGuard.requiresRepair(question, plan("QUERY_ORDER", "查询订单")));
    }
}
