package com.example.smartassistant.consumer.service.cache;

import com.example.smartassistant.common.cache.CacheVersionManager;
import com.example.smartassistant.common.intent.IntentTagGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteSemanticCacheServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private IntentTagGenerator tagGenerator;
    private RouteSemanticCacheService cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        tagGenerator = mock(IntentTagGenerator.class);
        CacheVersionManager versions = mock(CacheVersionManager.class);
        when(redis.opsForValue()).thenReturn(values);
        when(tagGenerator.generate(anyString())).thenReturn("weather,beijing");
        when(versions.getCurrentVersion()).thenReturn(3L);
        when(versions.refreshCurrentVersion()).thenReturn(3L);
        when(versions.isVersionValid(3L)).thenReturn(true);
        cache = new RouteSemanticCacheService(
                new StaticListableBeanFactory(Map.of("redis", redis)).getBeanProvider(StringRedisTemplate.class),
                new ObjectMapper(), tagGenerator,
                new StaticListableBeanFactory(Map.of("versions", versions)).getBeanProvider(CacheVersionManager.class),
                Duration.ofHours(1));
    }

    @Test
    void storesRoutingHintButNeverReplyContent() throws Exception {
        cache.save("北京天气", Map.of(
                "agentName", "router_fallback",
                "intentTag", "weather,beijing",
                "confidence", 0.9,
                "result", "private answer"));

        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values, org.mockito.Mockito.atLeastOnce())
                .set(anyString(), json.capture(), any(Duration.class));
        assertThat(json.getAllValues()).allSatisfy(value -> {
            assertThat(value).contains("router_fallback", "weather,beijing");
            assertThat(value).doesNotContain("private answer", "result");
        });
    }

    @Test
    void storesSuccessfulHintWhenResponseCarriesNullErrorField() {
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("agentName", "router_fallback");
        response.put("intentTag", "weather,beijing");
        response.put("confidence", 0.9);
        response.put("error", null);

        cache.save("北京天气", response);

        verify(values, org.mockito.Mockito.times(2))
                .set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void storesHintWithVersionRefreshedAfterRouterResponse() {
        CacheVersionManager versions = mock(CacheVersionManager.class);
        when(versions.refreshCurrentVersion()).thenReturn(4L);
        RouteSemanticCacheService refreshedCache = new RouteSemanticCacheService(
                new StaticListableBeanFactory(Map.of("redis", redis)).getBeanProvider(StringRedisTemplate.class),
                new ObjectMapper(), tagGenerator,
                new StaticListableBeanFactory(Map.of("versions", versions)).getBeanProvider(CacheVersionManager.class),
                Duration.ofHours(1));

        refreshedCache.save("北京天气", Map.of(
                "agentName", "router_fallback",
                "intentTag", "weather,beijing",
                "confidence", 0.9));

        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values, org.mockito.Mockito.atLeastOnce())
                .set(anyString(), json.capture(), any(Duration.class));
        assertThat(json.getAllValues()).allSatisfy(value ->
                assertThat(value).contains("\"cacheVersion\":4"));
        verify(versions).refreshCurrentVersion();
    }

    @Test
    void skipsClarificationAndErrors() {
        cache.save("查询订单", Map.of(
                "agentName", "order_agent", "clarification", true));
        cache.save("查询订单", Map.of(
                "agentName", "order_agent", "error", "down"));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
