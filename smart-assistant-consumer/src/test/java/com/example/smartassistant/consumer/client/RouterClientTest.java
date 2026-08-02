package com.example.smartassistant.consumer.client;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RouterClientTest {

    @Test
    void unwrapApiResponse_shouldReturnBusinessData() {
        Map<String, Object> data = Map.of(
                "result", "订单已发货",
                "agentName", "order_agent",
                "confidence", 0.95);

        Map<String, Object> result = RouterClient.unwrapApiResponse(Map.of(
                "code", 0,
                "message", "success",
                "data", data));

        assertEquals("订单已发货", result.get("result"));
        assertEquals("order_agent", result.get("agentName"));
    }

    @Test
    void unwrapApiResponse_shouldKeepLegacyFlatResponse() {
        Map<String, Object> legacy = Map.of("result", "ok", "agentName", "general");
        assertSame(legacy, RouterClient.unwrapApiResponse(legacy));
    }

    @Test
    void routingRequest_shouldKeepSessionAndRequestIdsSeparate() {
        Map<String, Object> request = RouterClient.buildRoutingRequestBody(
                "ORD-LOAD000001001", "1", "customer-session-a", "request-2");

        assertEquals(1L, request.get("userId"));
        assertEquals("customer-session-a", request.get("sessionId"));
        assertEquals("request-2", request.get("requestId"));
        assertEquals("ORD-LOAD000001001", request.get("question"));
    }
}
