package com.example.smartassistant.consumer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RouterClientDnsRetryTest {
    @Test void bothDnsAttemptsMustFailBeforeReturningNotSent() {
        var client = new RouterClient(null, new ObjectMapper(), 1000, 1000);
        var transport = mock(RestTemplate.class);
        ReflectionTestUtils.setField(client, "restTemplate", transport);
        ReflectionTestUtils.setField(client, "routerServiceUrl", "http://router");
        ReflectionTestUtils.setField(client, "dnsRetryDelayMs", 0L);
        when(transport.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("DNS", new UnknownHostException("router")));
        var result = client.callRouterRaw("查订单", "42", "session", "request", false);
        assertEquals("ROUTER_REQUEST_NOT_SENT", result.get("error"));
        verify(transport, times(2)).postForEntity(anyString(), any(HttpEntity.class), eq(Map.class));
    }

    @Test void dnsFailureWhileSavingCacheAfterBusinessExecutionIsNeverNotSent() {
        var client = new RouterClient(null, new ObjectMapper(), 1000, 1000);
        var transport = mock(RestTemplate.class);
        var cache = mock(com.example.smartassistant.consumer.service.cache.SelectiveSemanticAnswerCache.class);
        when(cache.find(anyLong(), anyString())).thenReturn(null);
        ReflectionTestUtils.setField(client, "restTemplate", transport);
        ReflectionTestUtils.setField(client, "routerServiceUrl", "http://router");
        ReflectionTestUtils.setField(client, "semanticAnswerCache", cache);
        when(transport.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("result", "已处理")));
        doThrow(new ResourceAccessException("DNS", new UnknownHostException("cache")))
                .when(cache).store(eq(42L), anyString(), anyMap());
        assertEquals("ROUTER_EXECUTION_UNCONFIRMED", client.callRouterRaw("查订单", "42", "session", "request", true).get("error"));
        verify(transport, times(1)).postForEntity(anyString(), any(HttpEntity.class), eq(Map.class));
    }

    @Test void dnsFailureIsNotSentButReadTimeoutIsUnknownAndNeitherLeaksTransportDetails() {
        var dns = RouterClient.routingFailure(new ResourceAccessException("private-host", new UnknownHostException("private-host")));
        assertEquals("ROUTER_REQUEST_NOT_SENT", dns.get("error"));
        assertFalse(dns.toString().contains("private-host"));
        var timeout = RouterClient.routingFailure(new ResourceAccessException("secret", new SocketTimeoutException()));
        assertEquals("ROUTER_EXECUTION_UNCONFIRMED", timeout.get("error"));
        assertFalse(timeout.toString().contains("secret"));
    }

    @Test
    void retriesDnsOnceWithTheSameRequest() {
        var client = new RouterClient(null, new ObjectMapper(), 1000, 1000);
        var transport = mock(RestTemplate.class);
        ReflectionTestUtils.setField(client, "restTemplate", transport);
        ReflectionTestUtils.setField(client, "dnsRetryDelayMs", 0L);
        var request = new HttpEntity<>(Map.of("requestId", "same-id"));
        when(transport.postForEntity("http://router", request, Map.class))
                .thenThrow(new ResourceAccessException("DNS", new UnknownHostException("router")))
                .thenReturn(ResponseEntity.ok(Map.of("result", "ok")));
        assertEquals("ok", client.postRoutingRequest("http://router", request).getBody().get("result"));
        verify(transport, times(2)).postForEntity("http://router", request, Map.class);
    }

    @Test
    void neverRepeatsAPostAfterAnAmbiguousReadTimeout() {
        var client = new RouterClient(null, new ObjectMapper(), 1000, 1000);
        var transport = mock(RestTemplate.class);
        ReflectionTestUtils.setField(client, "restTemplate", transport);
        var request = new HttpEntity<>(Map.of("requestId", "same-id"));
        when(transport.postForEntity("http://router", request, Map.class))
                .thenThrow(new ResourceAccessException("read", new SocketTimeoutException()));
        assertThrows(ResourceAccessException.class, () -> client.postRoutingRequest("http://router", request));
        verify(transport, times(1)).postForEntity("http://router", request, Map.class);
    }
}
