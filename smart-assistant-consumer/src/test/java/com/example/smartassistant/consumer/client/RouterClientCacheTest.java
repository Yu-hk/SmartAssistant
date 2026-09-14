package com.example.smartassistant.consumer.client;

import com.example.smartassistant.consumer.service.cache.SelectiveSemanticAnswerCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouterClientCacheTest {

    @Test
    void sentimentBypassSkipsBothCacheLookupAndStoreAndKeepsSessionIdentity() {
        var cache = mock(SelectiveSemanticAnswerCache.class);
        RouterClient client = org.mockito.Mockito.spy(new RouterClient(null, new ObjectMapper(), 100, 100));
        ReflectionTestUtils.setField(client, "semanticAnswerCache", cache);
        ReflectionTestUtils.setField(client, "routerServiceUrl", "http://router");
        org.mockito.Mockito.doReturn(org.springframework.http.ResponseEntity.ok(Map.of("result", "已查询订单")))
                .when(client).postRoutingRequest(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());

        assertThat(client.callRouterRaw("太慢了，查订单", "42", "owned", "request", false).get("result"))
                .isEqualTo("已查询订单");
        client.triggerRoutingDecision("太慢了，查订单", "42", "request", "owned", false);

        org.mockito.Mockito.verifyNoInteractions(cache);
        var request = org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
        verify(client, org.mockito.Mockito.times(2)).postRoutingRequest(org.mockito.ArgumentMatchers.anyString(), request.capture());
        for (var entity : request.getAllValues()) {
            assertThat(((Map<?, ?>) entity.getBody()).get("sessionId")).isEqualTo("owned");
            assertThat(((Map<?, ?>) entity.getBody()).get("question")).isEqualTo("太慢了，查订单");
        }
    }

    @Test
    void cacheHitReturnsWithoutCallingRouter() {
        SelectiveSemanticAnswerCache cache = mock(SelectiveSemanticAnswerCache.class);
        Map<String, Object> cached = Map.of(
                "result", "缓存回答",
                "workflowStatus", "COMPLETED",
                "fromCache", true);
        when(cache.find(42L, "推荐热门商品")).thenReturn(cached);
        RouterClient client = new RouterClient(null, new ObjectMapper(), 100, 100);
        ReflectionTestUtils.setField(client, "semanticAnswerCache", cache);

        Map<String, Object> result = client.callRouterRaw(
                "推荐热门商品", "42", "session-a", "request-a");

        assertThat(result).isEqualTo(cached);
        verify(cache).find(42L, "推荐热门商品");
    }
}
