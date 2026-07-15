package com.example.smartassistant.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracingGlobalFilterTest {

    private final TracingGlobalFilter filter = new TracingGlobalFilter();

    @Test
    void injectsGeneratedTraceIdIntoExchangeAttributes() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/x").build();
        MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
        GatewayFilterChainStub chain = new GatewayFilterChainStub();

        filter.filter(exchange, chain).block();

        Object tid = exchange.getAttributes().get(TracingGlobalFilter.TRACE_ID_KEY);
        assertNotNull(tid);
        assertTrue(((String) tid).startsWith("trace_"));
        assertTrue(chain.proceeded);
    }

    @Test
    void reusesTraceIdFromHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Trace-Id", "trace_incoming123");
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/x").headers(headers).build();
        MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();

        filter.filter(exchange, e -> Mono.empty()).block();

        assertTrue("trace_incoming123".equals(exchange.getAttributes().get(TracingGlobalFilter.TRACE_ID_KEY)));
    }

    static class GatewayFilterChainStub implements org.springframework.cloud.gateway.filter.GatewayFilterChain {
        boolean proceeded = false;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            proceeded = true;
            return Mono.empty();
        }
    }
}
