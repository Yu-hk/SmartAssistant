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
