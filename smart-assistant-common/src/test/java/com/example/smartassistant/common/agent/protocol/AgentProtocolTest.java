package com.example.smartassistant.common.agent.protocol;

import com.example.smartassistant.common.quality.DomainQualityResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requestAndResponseRoundTripAsVersionedJson() throws Exception {
        AgentExecutionRequest request = AgentExecutionRequest.answer(
                "req-1", "42", "查询天气", null);
        AgentExecutionRequest restoredRequest = mapper.readValue(
                mapper.writeValueAsString(request), AgentExecutionRequest.class);
        assertEquals("1.0", restoredRequest.protocolVersion());
        assertEquals("查询天气", restoredRequest.question());
        assertTrue(restoredRequest.predecessorOutputs().isEmpty());
        assertEquals("req-1", restoredRequest.traceId());

        AgentExecutionResponse response = AgentExecutionResponse.success(
                "晴", DomainQualityResult.pass(0.9, "WEATHER_OK"));
        AgentExecutionResponse restoredResponse = mapper.readValue(
                mapper.writeValueAsString(response), AgentExecutionResponse.class);
        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, restoredResponse.status());
        assertEquals("晴", restoredResponse.answer());
        assertTrue(restoredResponse.quality().toDomainQuality().isPass());
    }

    @Test
    void preservesTypedPredecessorOutputsAcrossJsonBoundary() throws Exception {
        AgentNodeOutput product = new AgentNodeOutput(
                "products", "product", "SUCCEEDED", "找到商品",
                Map.of("products", java.util.List.of(Map.of("sku", "SKU-1", "price", 99))));
        AgentExecutionRequest request = new AgentExecutionRequest(
                "1.0", "exec-1", "order", "42", "CREATE_ORDER", "下单",
                Map.of(), java.util.List.of("products"), java.util.List.of(), null,
                "order-exec-1", null, Map.of("products", product),
                "shopping", 3, "sha256:abc", 1, "trace-1");

        AgentExecutionRequest restored = mapper.readValue(
                mapper.writeValueAsString(request), AgentExecutionRequest.class);

        assertEquals("SKU-1", ((Map<?, ?>) ((java.util.List<?>) restored
                .predecessorOutputs().get("products").data().get("products")).getFirst()).get("sku"));
        assertEquals("order-exec-1", restored.idempotencyKey());
        assertEquals(1, restored.attempt());
    }
}
