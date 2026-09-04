package com.example.smartassistant.consumer.service.cache;

import com.example.smartassistant.common.cache.KnowledgeVersionManager;
import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelectiveSemanticAnswerCacheTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private ZSetOperations<String, String> zsets;
    private SemanticCacheEquivalenceVerifier verifier;
    private SelectiveSemanticAnswerCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        zsets = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(redisTemplate.opsForZSet()).thenReturn(zsets);
        ObjectProvider<BgeEmbeddingModel> embeddingProvider = mock(ObjectProvider.class);
        when(embeddingProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<SemanticCacheEquivalenceVerifier> verifierProvider = mock(ObjectProvider.class);
        verifier = mock(SemanticCacheEquivalenceVerifier.class);
        when(verifierProvider.getIfAvailable()).thenReturn(verifier);
        KnowledgeVersionManager versionManager = new KnowledgeVersionManager(() -> 7L, () -> 8L);
        cache = new SelectiveSemanticAnswerCache(
                redisTemplate, embeddingProvider, verifierProvider, versionManager, new ObjectMapper(),
                Duration.ofMinutes(1), Duration.ofHours(24),
                0.96, 0.94, 0.03, 0.98, 100, 500);
    }

    @Test
    void storesOnlyRouteApprovedResponsesWithStrictProductTtl() {
        Map<String, Object> response = Map.of(
                "result", "当前热门商品为 A",
                "workflowStatus", "COMPLETED",
                "semanticCacheCategory", "PRODUCT_CONSULTATION",
                "semanticCacheEligible", true,
                "semanticCacheVolatileProduct", true);

        cache.store(42L, "推荐热门商品", response);

        verify(values).set(anyString(), anyString(), any(Duration.class));
        verify(values).set(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(1)));
    }

    @Test
    void doesNotStoreOtherCategoriesEvenWhenCallerMarksThemEligible() {
        cache.store(42L, "讲个笑话", Map.of(
                "result", "回答",
                "workflowStatus", "COMPLETED",
                "semanticCacheCategory", "NONE",
                "semanticCacheEligible", true));

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void businessLookupUsesCurrentKnowledgeVersionAndReturnsBeforeRoutePayload() throws Exception {
        SelectiveSemanticAnswerCache.CacheEntry entry = new SelectiveSemanticAnswerCache.CacheEntry(
                "如何申请售后", "请在订单详情中申请售后", "order", "BUSINESS",
                0.95, "SINGLE_AGENT", List.of("order"),
                "BUSINESS_CONSULTATION", false, 7L,
                System.currentTimeMillis() + 60_000L, List.of());
        String json = new ObjectMapper().writeValueAsString(entry);
        when(values.get(contains("product_consultation:u42:v0"))).thenReturn(null);
        when(values.get(contains("business_consultation:u42:v7"))).thenReturn(json);

        Map<String, Object> result = cache.find(42L, "如何申请售后");

        assertThat(result).isNotNull();
        assertThat(result.get("result")).isEqualTo("请在订单详情中申请售后");
        assertThat(result.get("fromCache")).isEqualTo(true);
        assertThat(result.get("totalTokens")).isEqualTo(0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void semanticCandidateRequiresHighPrecisionEquivalenceVerification() throws Exception {
        BgeEmbeddingModel embedding = mock(BgeEmbeddingModel.class);
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedding(anyString())).thenReturn(new float[]{1f, 0f});
        ObjectProvider<BgeEmbeddingModel> embeddingProvider = mock(ObjectProvider.class);
        when(embeddingProvider.getIfAvailable()).thenReturn(embedding);
        ObjectProvider<SemanticCacheEquivalenceVerifier> verifierProvider = mock(ObjectProvider.class);
        when(verifierProvider.getIfAvailable()).thenReturn(verifier);
        cache = new SelectiveSemanticAnswerCache(
                redisTemplate, embeddingProvider, verifierProvider,
                new KnowledgeVersionManager(() -> 7L, () -> 8L), new ObjectMapper(),
                Duration.ofMinutes(1), Duration.ofHours(24),
                0.96, 0.94, 0.03, 0.98, 100, 500);

        SelectiveSemanticAnswerCache.CacheEntry entry = new SelectiveSemanticAnswerCache.CacheEntry(
                "推荐当前热门商品", "热门商品为 A", "product", "PRODUCT",
                0.99, "SINGLE_AGENT", List.of("product"),
                "PRODUCT_CONSULTATION", true, 0L,
                System.currentTimeMillis() + 60_000L, List.of(1f, 0f));
        String json = new ObjectMapper().writeValueAsString(entry);
        when(values.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return key.contains(":entry:product_consultation:") ? json : null;
        });
        when(zsets.reverseRange(anyString(), any(Long.class), any(Long.class)))
                .thenReturn(Set.of("candidate"));
        when(verifier.verify("PRODUCT_CONSULTATION", "推荐当前热门商品", "最近有哪些热销商品"))
                .thenReturn(new SemanticCacheEquivalenceVerifier.Verification(
                        true, true, false, true, 0.99, "equivalent"));

        Map<String, Object> result = cache.find(42L, "最近有哪些热销商品");

        assertThat(result).isNotNull();
        assertThat(result.get("result")).isEqualTo("热门商品为 A");
    }

    @Test
    @SuppressWarnings("unchecked")
    void semanticCandidateWithAdditionalIntentIsRejected() throws Exception {
        BgeEmbeddingModel embedding = mock(BgeEmbeddingModel.class);
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedding(anyString())).thenReturn(new float[]{1f, 0f});
        ObjectProvider<BgeEmbeddingModel> embeddingProvider = mock(ObjectProvider.class);
        when(embeddingProvider.getIfAvailable()).thenReturn(embedding);
        ObjectProvider<SemanticCacheEquivalenceVerifier> verifierProvider = mock(ObjectProvider.class);
        when(verifierProvider.getIfAvailable()).thenReturn(verifier);
        cache = new SelectiveSemanticAnswerCache(
                redisTemplate, embeddingProvider, verifierProvider,
                new KnowledgeVersionManager(() -> 7L, () -> 8L), new ObjectMapper(),
                Duration.ofMinutes(1), Duration.ofHours(24),
                0.96, 0.94, 0.03, 0.98, 100, 500);
        SelectiveSemanticAnswerCache.CacheEntry entry = new SelectiveSemanticAnswerCache.CacheEntry(
                "推荐当前热门商品", "热门商品为 A", "product", "PRODUCT",
                0.99, "SINGLE_AGENT", List.of("product"),
                "PRODUCT_CONSULTATION", true, 0L,
                System.currentTimeMillis() + 60_000L, List.of(1f, 0f));
        String json = new ObjectMapper().writeValueAsString(entry);
        when(values.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return key.contains(":entry:product_consultation:") ? json : null;
        });
        when(zsets.reverseRange(anyString(), any(Long.class), any(Long.class)))
                .thenReturn(Set.of("candidate"));
        when(verifier.verify("PRODUCT_CONSULTATION", "推荐当前热门商品", "推荐热门商品并查询我的物流"))
                .thenReturn(new SemanticCacheEquivalenceVerifier.Verification(
                        false, false, true, false, 0.99, "additional_intent"));

        assertThat(cache.find(42L, "推荐热门商品并查询我的物流")).isNull();
    }
}
