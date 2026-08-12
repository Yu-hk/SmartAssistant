package com.example.smartassistant.common.agent.protocol;

import com.example.smartassistant.common.quality.DomainQualityResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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

        AgentExecutionResponse response = AgentExecutionResponse.success(
                "晴", DomainQualityResult.pass(0.9, "WEATHER_OK"));
        AgentExecutionResponse restoredResponse = mapper.readValue(
                mapper.writeValueAsString(response), AgentExecutionResponse.class);
        assertEquals(AgentExecutionResponse.Status.SUCCEEDED, restoredResponse.status());
        assertEquals("晴", restoredResponse.answer());
        assertTrue(restoredResponse.quality().toDomainQuality().isPass());
    }
}
