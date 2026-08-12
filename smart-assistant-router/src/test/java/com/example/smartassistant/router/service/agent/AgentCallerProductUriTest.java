package com.example.smartassistant.router.service.agent;

import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentCallerProductUriTest {

    @Test
    void productUsesUnifiedInternalEndpointWithoutQuestionInQueryString() {
        String question = "办公 笔记本电脑";
        var uri = AgentCallerService.buildProcessUri(
                "http://product:8084", "product", question);

        assertEquals("http://product:8084/internal/agents/product/execute", uri.toString());
        assertEquals(null, uri.getRawQuery());
    }

    @Test
    void parsesTypedProtocolResponseAndKeepsLegacyFallbackUri() {
        AgentCallerService service = new AgentCallerService(null, null, null);
        AgentExecutionResponse response = ReflectionTestUtils.invokeMethod(
                service, "parseProtocolResponse",
                "{\"protocolVersion\":\"1.0\",\"status\":\"SUCCEEDED\","
                        + "\"answer\":\"ok\",\"data\":{},\"error\":null,"
                        + "\"quality\":{\"status\":\"PASS\",\"score\":0.9,"
                        + "\"reasonCodes\":[\"OK\"]}}");

        assertEquals("ok", response.answer());
        assertEquals("http://product:8084/product/stream/chat/sync?message=%E5%8A%9E%E5%85%AC",
                AgentCallerService.buildLegacyProcessUri(
                        "http://product:8084", "product", "办公").toString());
    }

    @Test
    void generalHasNoRemoteLegacyEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentCallerService.buildLegacyProcessUri(
                        "http://removed-general:8086", "general", "你好"));
    }
}
