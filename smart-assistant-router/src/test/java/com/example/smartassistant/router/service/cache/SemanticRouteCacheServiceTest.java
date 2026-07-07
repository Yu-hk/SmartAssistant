/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SemanticRouteCacheServiceTest {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ChineseTokenizer tokenizer;
    @Mock private AgentDiscoveryService agentDiscoveryService;
    @Mock private ValueOperations<String, String> valueOps;
    private TfEmbeddingService tfEmbedding;
    private VectorCacheStore vectorCache;
    private BgeOnnxEmbeddingService bgeEmbedding;

    private SemanticRouteCacheService cacheService;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        tfEmbedding = new TfEmbeddingService(tokenizer);
        vectorCache = new VectorCacheStore();
        bgeEmbedding = new BgeOnnxEmbeddingService();
        cacheService = new SemanticRouteCacheService(chatClientBuilder, redisTemplate, tokenizer,
                agentDiscoveryService, tfEmbedding, vectorCache, bgeEmbedding, null, null);
        ReflectionTestUtils.setField(cacheService, "cacheEnabled", true);
    }

    @Test
    @DisplayName("禁用缓存时返回 null")
    void testDisabled() {
        cacheService = new SemanticRouteCacheService(chatClientBuilder, redisTemplate, tokenizer,
                agentDiscoveryService, tfEmbedding, vectorCache, bgeEmbedding, null, null) {
            @Override public String generateIntentTag(String q) { return null; }
        };
        assertNull(cacheService.getCachedDecision("test"));
    }

    @Test
    @DisplayName("保存决策时保存关键词哈希和精确映射")
    void testSaveDecisionSavesKeywordHash() {
        when(tokenizer.tokenize(anyString())).thenReturn(Set.of("上海", "天气"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        cacheService.saveDecision("req-1", "上海天气", "location_weather", 0.5, 1L, "天气查询");

        verify(valueOps).set(startsWith("a2a:route:keyword:"), eq("天气查询"), anyLong(), eq(TimeUnit.SECONDS));
        verify(valueOps).set(startsWith("a2a:route:exact:"), eq("天气查询"), anyLong(), eq(TimeUnit.SECONDS));
        verify(valueOps).set(startsWith("a2a:route:semantic:"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("提取关键词为空时 getCachedDecision 返回 null")
    void testGetKeywordHashWithEmptyTokens() {
        lenient().when(tokenizer.tokenize(anyString())).thenReturn(Set.of());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);

        assertNull(cacheService.getCachedDecision("a"));
    }

    @Test
    @DisplayName("同用户 hitCount=2 时使用个性化前缀")
    void testWrapCachedReplySameUser() {
        var decision = new SemanticRouteCacheService.CachedRouteDecision("tag", "agent", 0.5, "上海天气");
        decision.reply = "天气很好";
        decision.hitCount = 2;
        decision.firstCachedAt = System.currentTimeMillis() - 1000;
        decision.firstUserId = 1L;

        String result = cacheService.wrapCachedReply("天气很好", decision, "上海天气", 1L);
        assertTrue(result.contains("再帮你查一次") || result.contains("跟上次查询") || result.contains("还是同样的结果"),
                "同用户应使用个性化前缀，实际: " + result);
    }

    @Test
    @DisplayName("不同用户 hitCount=2 时使用中性前缀")
    void testWrapCachedReplyDiffUser() {
        var decision = new SemanticRouteCacheService.CachedRouteDecision("tag", "agent", 0.5, "上海天气");
        decision.reply = "天气很好";
        decision.hitCount = 2;
        decision.firstCachedAt = System.currentTimeMillis() - 1000;
        decision.firstUserId = 1L;

        String result = cacheService.wrapCachedReply("天气很好", decision, "上海天气", 2L);
        assertTrue(result.contains("查询结果如下") || result.contains("以下是相关信息") || result.contains("这是查询到的结果"),
                "不同用户应使用中性前缀，实际: " + result);
    }

    @Test
    @DisplayName("不同用户 hitCount>=3 使用中性前缀")
    void testNeutralPrefixForDifferentUser() {
        var decision = new SemanticRouteCacheService.CachedRouteDecision("tag", "agent", 0.5, "上海天气");
        decision.reply = "天气很好";
        decision.hitCount = 5;
        decision.firstCachedAt = System.currentTimeMillis() - 1000;
        decision.firstUserId = 1L;

        // 与前缀变化逻辑保持一致：不包含"再帮你查"、"之前查到"、"上次查询"等个性化用语
        String result = cacheService.wrapCachedReply("天气很好", decision, "上海天气", 2L);
        assertFalse(result.contains("之前查到"), "不同用户不应包含个性化用语，实际: " + result);
        assertFalse(result.contains("上次查询"), "不同用户不应包含个性化用语，实际: " + result);
        assertFalse(result.contains("再帮你查"), "不同用户不应包含个性化用语，实际: " + result);
    }

    @Test
    @DisplayName("同用户 hitCount>=3 使用个性化前缀")
    void testSameUserHighHit() {
        var decision = new SemanticRouteCacheService.CachedRouteDecision("tag", "agent", 0.5, "上海天气");
        decision.reply = "天气很好";
        decision.hitCount = 5;
        decision.firstCachedAt = System.currentTimeMillis() - 1000;
        decision.firstUserId = 1L;

        String result = cacheService.wrapCachedReply("天气很好", decision, "上海天气", 1L);
        assertTrue(result.contains("之前查到的") || result.contains("之前查询的") || result.contains("上次查询"),
                "同用户应使用个性化前缀，实际: " + result);
    }

    private String md5(String str) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(str.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return str; }
    }
}
