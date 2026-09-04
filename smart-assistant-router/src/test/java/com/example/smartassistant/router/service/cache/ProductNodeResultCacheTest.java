package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.SubTaskResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductNodeResultCacheTest {

    @Test
    @SuppressWarnings("unchecked")
    void storesAndRestoresOnlyVerifiedProductNodeResult() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        ProductNodeResultCache cache = new ProductNodeResultCache(
                provider, new ObjectMapper(), Duration.ofMinutes(1));
        IntentGraph.IntentNode node = node("product", "QUERY_HOT_PRODUCTS");
        SubTaskResult result = new SubTaskResult(
                "p1", "查询热门商品", "product", "商品 A", true);
        result.setDomainQuality(DomainQualityResult.pass(1.0, "VERIFIED"));

        cache.store(node, 42L, Map.of("category", "手机"), "偏好苹果", Map.of(), result);

        var key = org.mockito.ArgumentCaptor.forClass(String.class);
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), json.capture(), any(Duration.class));
        when(values.get(key.getValue())).thenReturn(json.getValue());
        SubTaskResult cached = cache.find(
                node, 42L, Map.of("category", "手机"), "偏好苹果", Map.of());

        assertThat(cached).isNotNull();
        assertThat(cached.getResult()).isEqualTo("商品 A");
        assertThat(cached.getStructuredData()).containsEntry(
                ProductNodeResultCache.CACHE_HIT_KEY, true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void neverCachesUserBoundOrderNode() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        ProductNodeResultCache cache = new ProductNodeResultCache(
                provider, new ObjectMapper(), Duration.ofMinutes(1));
        IntentGraph.IntentNode node = node("order", "TRACK_LOGISTICS");
        SubTaskResult result = new SubTaskResult(
                "o1", "查询物流", "order", "运输中", true);
        result.setDomainQuality(DomainQualityResult.pass(1.0, "VERIFIED"));

        cache.store(node, 42L, Map.of("orderId", 1), null, Map.of(), result);

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(cache.find(node, 42L, Map.of(), null, Map.of())).isNull();
    }

    private static IntentGraph.IntentNode node(String agent, String operation) {
        return new IntentGraph.IntentNode(
                "n1", "执行子任务", agent, List.of(), null, List.of(), false,
                operation, Map.of(), List.of(), null, "READ");
    }
}
