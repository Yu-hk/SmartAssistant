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
