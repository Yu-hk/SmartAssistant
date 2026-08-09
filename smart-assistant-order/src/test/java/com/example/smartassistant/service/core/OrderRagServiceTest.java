package com.example.smartassistant.service.core;

import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderRagServiceTest {

    @Test
    void refundPolicyUsesPublicKnowledgeWithoutOrderId() {
        OrderDataProvider orderData = mock(OrderDataProvider.class);
        OrderRagService service = new OrderRagService(orderData);

        RetrievalQualityResult result = service.retrieveWithQualityResult(
                OrderIntentService.IntentType.REFUND_POLICY,
                "商品退货退款需要满足哪些条件？");

        assertFalse(result.isRejected());
        assertTrue(result.isHighQuality());
        assertTrue(result.getContent().contains("7天无理由退货"));
        assertTrue(result.getContent().contains("商品完好"));
        String answer = service.buildRefundPolicyAnswer(result);
        assertTrue(answer.startsWith("根据当前退货退款政策"));
        assertTrue(answer.contains("商品完好"));
        verifyNoInteractions(orderData);
    }
}
