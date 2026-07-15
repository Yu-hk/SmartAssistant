package com.example.smartassistant.gateway.exception;

import com.example.smartassistant.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void securityException_backfillsTraceIdFromExchange() {
        MockServerWebExchange exchange = newExchangeWithTraceId("trace_abc123");
        var resp = handler.handleSecurityException(new SecurityException("bad token"), exchange);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        ApiResponse.ErrorDetail err = Objects.requireNonNull(resp.getBody()).getError();
        assertEquals("trace_abc123", err.getTraceId());
        assertEquals("GATEWAY_002", err.getType());
    }

    @Test
    void generalException_backfillsTraceId() {
        MockServerWebExchange exchange = newExchangeWithTraceId("trace_gen999");
        var resp = handler.handleGeneralException(new RuntimeException("boom"), exchange);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        ApiResponse.ErrorDetail err = Objects.requireNonNull(resp.getBody()).getError();
        assertEquals("trace_gen999", err.getTraceId());
        assertEquals("GATEWAY_099", err.getType());
    }

    @Test
    void responseStatusException_usesStatusCodeInCode() {
        MockServerWebExchange exchange = newExchangeWithTraceId("trace_rs");
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream down");
        var resp = handler.handleResponseStatusException(ex, exchange);
        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertEquals("GATEWAY_502", Objects.requireNonNull(resp.getBody()).getError().getType());
    }

    @Test
    void webClientException_mapsAuthToGateway002() {
        MockServerWebExchange exchange = newExchangeWithTraceId("trace_wc");
        org.springframework.web.reactive.function.client.WebClientResponseException ex =
                org.springframework.web.reactive.function.client.WebClientResponseException.create(
                        HttpStatus.UNAUTHORIZED.value(), "unauthorized", null, null, null);
        var resp = handler.handleWebClientException(ex, exchange);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("GATEWAY_002", Objects.requireNonNull(resp.getBody()).getError().getType());
    }

    private MockServerWebExchange newExchangeWithTraceId(String traceId) {
        MockServerWebExchange exchange = MockServerWebExchange.builder(
                MockServerHttpRequest.get("/api/x").build()).build();
        exchange.getAttributes().put("traceId", traceId);
        return exchange;
    }
}
