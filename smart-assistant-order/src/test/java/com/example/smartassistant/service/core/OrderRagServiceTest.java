package com.example.smartassistant.service.core;

import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.smartassistant.common.tool.spi.dto.OrderDTO;

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

    @Test
    void lifecycleGuidanceDoesNotRequireOrderIdOrTouchOrderData() {
        OrderDataProvider orderData = mock(OrderDataProvider.class);
        OrderRagService service = new OrderRagService(orderData);

        RetrievalQualityResult result = service.retrieveWithQualityResult(
                OrderIntentService.IntentType.ORDER_GUIDANCE,
                "请说明下单后如何查询、取消和申请售后");

        assertFalse(result.isRejected());
        assertTrue(result.isHighQuality());
        String answer = service.buildOrderGuidanceAnswer(result);
        assertTrue(answer.contains("查询订单"));
        assertTrue(answer.contains("取消订单"));
        assertTrue(answer.contains("申请售后"));
        assertTrue(answer.contains("查看退款进度"));
        assertTrue(answer.contains("售后单号"));
        assertTrue(answer.contains("不会创建、取消或退款任何订单"));
        verifyNoInteractions(orderData);
    }

    @Test
    void preOrderChecklistDoesNotCreateOrderOrRequireOrderId() {
        OrderDataProvider orderData = mock(OrderDataProvider.class);
        OrderRagService service = new OrderRagService(orderData);

        RetrievalQualityResult result = service.retrieveWithQualityResult(
                OrderIntentService.IntentType.ORDER_PREPARATION_GUIDANCE,
                "说明创建订单前需要确认哪些信息，但不要执行下单");

        assertFalse(result.isRejected());
        assertTrue(result.getContent().contains("商品"));
        assertTrue(result.getContent().contains("收货人姓名"));
        assertTrue(result.getContent().contains("不会执行下单"));
        assertTrue(result.getContent().contains("不会创建测试订单"));
        verifyNoInteractions(orderData);
    }

    @Test
    void fulfillmentIntentsRetrieveCurrentOrderEvidence() {
        OrderDataProvider orderData = mock(OrderDataProvider.class);
        when(orderData.findOrderByOrderId("ORD-9001")).thenReturn(OrderDTO.builder()
                .orderId("ORD-9001").userId(42L).productName("测试商品")
                .status("待付款").build());
        OrderRagService service = new OrderRagService(orderData);

        for (OrderIntentService.IntentType intent : java.util.List.of(
                OrderIntentService.IntentType.PAY,
                OrderIntentService.IntentType.SHIP,
                OrderIntentService.IntentType.TRACK_LOGISTICS,
                OrderIntentService.IntentType.CONFIRM_DELIVERY)) {
            RetrievalQualityResult result = service.retrieveWithQualityResult(
                    intent, "处理订单 ORD-9001");
            assertFalse(result.isRejected(), () -> "intent should have evidence: " + intent);
            assertTrue(result.getContent().contains("ORD-9001"));
        }
    }

    @Test
    void logisticsRetrievalAcceptsBulkOrderId() {
        OrderDataProvider orderData = mock(OrderDataProvider.class);
        when(orderData.findOrderByOrderId("BULK-0002")).thenReturn(OrderDTO.builder()
                .orderId("BULK-0002").userId(42L).productName("测试商品")
                .status("已发货").build());
        OrderRagService service = new OrderRagService(orderData);

        RetrievalQualityResult result = service.retrieveWithQualityResult(
                OrderIntentService.IntentType.TRACK_LOGISTICS,
                "查询 bulk-0002 的物流信息");

        assertFalse(result.isRejected());
        assertTrue(result.getContent().contains("BULK-0002"));
    }
}
