package com.example.smartassistant.common.jev;

import com.example.smartassistant.common.audit.TokenUsageCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JevDecisionClientTest {
    @Test
    void disabledNeverCallsProvider() {
        var client = new JevDecisionClient(new ObjectMapper(), false, "", "jev-latest", 500,
                URI.create("http://127.0.0.1:1/v1/systemone"));
        assertTrue(client.evaluate("查询订单", Map.of("intent", Map.of("type", "choice")), "jev-disabled").isEmpty());
        assertFalse(TokenUsageCache.hasEntry("jev-disabled"));
    }

    @Test
    void sendsMinimizedRequestAndAccountsForMeasuredTokens() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] result = "{\"answers\":{\"intent\":{\"choice\":\"product\",\"confidence\":0.98}},"
                    .concat("\"usage\":{\"input_tokens\":12,\"output_tokens\":4}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, result.length);
            try (var output = exchange.getResponseBody()) { output.write(result); }
        });
        server.start();
        try {
            var client = new JevDecisionClient(new ObjectMapper(), true, "test-only-key", "jev-latest", 1000,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone"));
            var answer = client.evaluate("手机号13800138000，订单ORD-ABC123，邮箱a@example.com，查耳机",
                    Map.of("intent", Map.of("type", "choice")), "jev-measured");
            assertEquals("product", answer.orElseThrow().choice("intent"));
            assertEquals(0.98, answer.orElseThrow().confidence("intent"));
            assertEquals("Bearer test-only-key", authorization.get());
            assertFalse(body.get().contains("13800138000"));
            assertFalse(body.get().contains("ORD-ABC123"));
            assertFalse(body.get().contains("a@example.com"));
            assertEquals(16, TokenUsageCache.consume("jev-measured").totalTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsOversizedTextBeforeNetwork() {
        var client = new JevDecisionClient(new ObjectMapper(), true, "test-only-key", "jev-latest", 500,
                URI.create("http://127.0.0.1:1/v1/systemone"));
        assertTrue(client.evaluate("x".repeat(1501), Map.of("intent", Map.of("type", "choice")), null).isEmpty());
    }

    @Test
    void failedProviderCallLeavesTokenTotalUnknown() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var client = new JevDecisionClient(new ObjectMapper(), true, "test-only-key", "jev-latest", 1000,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone"));
            assertTrue(client.evaluate("查询耳机", Map.of("intent", Map.of("type", "choice")),
                    "jev-provider-unavailable").isEmpty());
            assertTrue(TokenUsageCache.hasEntry("jev-provider-unavailable"));
            assertNull(TokenUsageCache.consume("jev-provider-unavailable"));
        } finally {
            server.stop(0);
        }
    }
}
