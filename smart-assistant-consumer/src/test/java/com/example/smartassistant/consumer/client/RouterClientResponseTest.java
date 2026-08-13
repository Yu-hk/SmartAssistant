package com.example.smartassistant.consumer.client;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouterClientResponseTest {
    @Test
    void unwrapsUnifiedApiResponse() {
        Map<String, Object> payload = Map.of("result", "hello", "agentName", "general");
        Map<String, Object> result = RouterClient.unwrapRouterResponse(Map.of("code", 0, "data", payload));
        assertEquals("hello", result.get("result"));
        assertEquals("general", result.get("agentName"));
    }

    @Test
    void preservesLegacyFlatResponse() {
        Map<String, Object> flat = Map.of("result", "legacy");
        assertSame(flat, RouterClient.unwrapRouterResponse(flat));
    }


    @Test
    void rejectsAnonymousIdentityBeforeCallingRouter() {
        RouterClient client = new RouterClient(null, new com.fasterxml.jackson.databind.ObjectMapper(), 1000, 1000);
        assertThrows(IllegalArgumentException.class,
                () -> client.callRouterRaw("hello", "anonymous", "session", "request"));
    }
}
