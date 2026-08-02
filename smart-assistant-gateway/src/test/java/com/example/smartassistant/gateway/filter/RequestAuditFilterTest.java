package com.example.smartassistant.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RequestAuditFilterTest {

    private final RequestAuditFilter filter = new RequestAuditFilter();

    @Test
    void usesBusinessRequestIdFromQueryParameter() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/assistant/api/math/stream/chat")
                        .queryParam("requestId", "LOGTEST-20260726-AGENT-010")
                        .build());

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, current -> complete(current, forwarded)).block();

        assertEquals("LOGTEST-20260726-AGENT-010",
                forwarded.get().getRequest().getHeaders().getFirst("X-Request-Id"));
        assertEquals("LOGTEST-20260726-AGENT-010",
                exchange.getResponse().getHeaders().getFirst("X-Request-Id"));
    }

    @Test
    void headerTakesPrecedenceOverQueryParameter() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/assistant/api/math/stream/chat")
                        .header("X-Request-Id", "header-id")
                        .queryParam("requestId", "query-id")
                        .build());

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, current -> complete(current, forwarded)).block();

        assertEquals("header-id",
                forwarded.get().getRequest().getHeaders().getFirst("X-Request-Id"));
    }

    @Test
    void rejectsUnsafeQueryRequestId() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/assistant/api/math/stream/chat")
                        .queryParam("requestId", "unsafe value")
                        .build());

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, current -> complete(current, forwarded)).block();

        String generated = forwarded.get().getRequest().getHeaders().getFirst("X-Request-Id");
        assertNotEquals("unsafe value", generated);
        assertEquals(16, generated.length());
    }

    private Mono<Void> complete(ServerWebExchange exchange,
                                AtomicReference<ServerWebExchange> forwarded) {
        forwarded.set(exchange);
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return exchange.getResponse().setComplete();
    }
}
