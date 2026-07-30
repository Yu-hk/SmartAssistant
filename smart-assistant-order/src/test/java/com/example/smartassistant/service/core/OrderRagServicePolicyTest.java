package com.example.smartassistant.service.core;

import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import org.junit.jupiter.api.Test;

import static com.example.smartassistant.service.core.OrderIntentService.IntentType.POLICY_QA;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderRagServicePolicyTest {

    @Test
    void policyQuestionUsesKnowledgeBaseWithoutRequiringOrderId() {
        OrderDataProvider orderData = mock(OrderDataProvider.class);
        KnowledgeRetrievalService knowledge = mock(KnowledgeRetrievalService.class);
        String question = "非质量原因退货的运费由谁承担？";
        when(knowledge.search("order_knowledge", question, 5))
                .thenReturn("七天无理由退货由用户承担运费，质量问题由平台承担。");

        RetrievalQualityResult result = new OrderRagService(orderData, knowledge)
                .retrieveWithQualityResult(POLICY_QA, question);

        assertFalse(result.isRejected());
        assertTrue(result.getContent().contains("用户承担运费"));
        verify(knowledge).search("order_knowledge", question, 5);
        verifyNoInteractions(orderData);
    }

    @Test
    void missingPolicyEvidenceReturnsKnowledgeSpecificMessage() {
        OrderDataProvider orderData = mock(OrderDataProvider.class);
        KnowledgeRetrievalService knowledge = mock(KnowledgeRetrievalService.class);
        String question = "某项不存在的特殊政策";
        when(knowledge.search("order_knowledge", question, 5))
                .thenReturn("INSUFFICIENT_EVIDENCE: 未找到");

        RetrievalQualityResult result = new OrderRagService(orderData, knowledge)
                .retrieveWithQualityResult(POLICY_QA, question);

        assertTrue(result.isRejected());
        assertTrue(result.getRejectionMessage().contains("订单政策"));
        verifyNoInteractions(orderData);
    }
}
