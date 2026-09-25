package com.example.smartassistant.service.core;

import com.example.smartassistant.common.agent.protocol.ClarificationRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderClarificationServiceTest {
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
