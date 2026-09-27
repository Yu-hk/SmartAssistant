package com.example.smartassistant.service.core;

import com.example.smartassistant.common.agent.protocol.ClarificationRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderClarificationServiceTest {
    @Test void explicitContactDifferenceIsFlaggedWithoutReplacingCurrentInput() {
        var history = mock(OrderCheckoutHistoryService.class);
        var input = Map.<String, Object>of("product", "耳机", "recipientName", "张三",
                "recipientPhone", "13900000000", "shippingAddress", "北京市朝阳区建国路1号");
        when(history.conflicts(7L, input)).thenReturn(List.of("recipientPhone"));
        var response = OrderClarificationService.prepare("CREATE_ORDER", input, "7", history);
        assertTrue(response.answer().contains("联系电话与单笔历史订单资料不一致"));
        assertEquals("13900000000", input.get("recipientPhone"));
    }
    @Test void createCollectsDeliveryDataNotRecommendationOrServerPrice() {
        var response = OrderClarificationService.prepare("CREATE_ORDER", Map.of("product_name", "AirPods Pro", "budget", "2000", "weight", "3"));
        var contract = ClarificationRequest.read(response.data().get("clarificationRequest"));
        assertEquals(List.of("recipientName", "recipientPhone", "shippingAddress"), contract.fields());
        assertFalse(contract.fields().contains("amount"));
    }
    @Test void afterSalesOnlyCollectsMissingOrderInputs() {
        var response = OrderClarificationService.prepare("REFUND_ORDER", Map.of("order_id", "ORD-1"));
        assertEquals(List.of("reason"), ClarificationRequest.read(response.data().get("clarificationRequest")).fields());
        assertEquals(List.of("orderNumber"), ClarificationRequest.read(OrderClarificationService.prepare(
                "TRACK_LOGISTICS", Map.of("order_id", "garbage")).data().get("clarificationRequest")).fields());
    }
    @Test void listAndCompletePreparationDoNotInventMissingFields() {
        assertFalse(OrderClarificationService.prepare("QUERY_ORDER_LIST", Map.of()).data().containsKey("clarificationRequest"));
        assertFalse(OrderClarificationService.prepare("CREATE_ORDER", Map.of("product_name", "耳机", "recipient_name", "测试",
                "recipient_phone", "13800000000", "shipping_address", "北京市测试路1号")).data().containsKey("clarificationRequest"));
    }
    @Test void cityAloneIsNotAUsableShippingAddress() {
        var input = Map.<String, Object>of("product_name", "笔记本电脑", "recipient_name", "测试",
                "recipient_phone", "13800000000", "shipping_address", "北京市");
        var response = OrderClarificationService.prepare("CREATE_ORDER", input);
        assertEquals(List.of("shippingAddress"), ClarificationRequest.read(
                response.data().get("clarificationRequest")).fields());
        assertTrue(response.answer().contains("门牌"));
    }
}
