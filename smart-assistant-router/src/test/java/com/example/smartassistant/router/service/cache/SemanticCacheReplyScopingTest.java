/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.cache.CacheVersionManager;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import com.example.smartassistant.router.service.cache.BgeOnnxEmbeddingService;
import com.example.smartassistant.router.service.cache.TfEmbeddingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P3-B 缓存策略对齐单元测试（对标文章⑥：不缓存可变最终回答 / 权限+版本进入缓存 key）。
 * <p>验证：</p>
 * <ul>
 *   <li>回复缓存 key 含租户作用域 → 跨租户隔离（租户 A 的回复不会被租户 B 命中）；</li>
 *   <li>回复缓存 key 含索引版本作用域 → 版本递增后旧回复自然失效；</li>
 *   <li>{@code reply-cache-enabled=false} 时任何最终回复都不写入缓存。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SemanticCacheReplyScopingTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ChineseTokenizer tokenizer;
    @Mock
    private AgentDiscoveryService agentDiscovery;
    @Mock
    private CacheVersionManager cacheVersionManager;

    private SemanticRouteCacheService cache;
    private Map<String, String> store;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.expire(anyString(), anyLong(), any())).thenReturn(true);
        lenient().when(redisTemplate.opsForValue().increment(anyString())).thenReturn(1L);
        lenient().when(tokenizer.tokenize(anyString())).thenReturn(java.util.Set.of("北京", "天气"));
        lenient().when(agentDiscovery.getAgentTtl(anyString())).thenReturn(7200L);
        lenient().when(agentDiscovery.isAlwaysCacheReply(anyString())).thenReturn(true);
        lenient().when(cacheVersionManager.isVersionValid(anyLong())).thenReturn(true);

        cache = new SemanticRouteCacheService(
                null, redisTemplate, tokenizer, agentDiscovery,
                new TfEmbeddingService(tokenizer),
                null, new BgeOnnxEmbeddingService(), null, cacheVersionManager);
        ReflectionTestUtils.setField(cache, "cacheEnabled", true);
        ReflectionTestUtils.setField(cache, "replyCacheEnabled", true);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    // ==================== 1. 租户隔离：回复 key 含租户作用域 ====================

    @Test
    @DisplayName("回复缓存 key 含租户作用域：T1 与 T2 写入不同的 key")
    void replyKeyScopedByTenant() {
        MDC.put("tenantId", "T1");
        cache.saveReply("北京天气", "T1的回复", "location_weather", "北京,天气");

        MDC.put("tenantId", "T2");
        cache.saveReply("北京天气", "T2的回复", "location_weather", "北京,天气");

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps, atLeastOnce()).set(captor.capture(), anyString(), anyLong(), any());
        var replyKeys = captor.getAllValues().stream()
                .filter(k -> k.startsWith("a2a:route:reply:"))
                .toList();
        assertTrue(replyKeys.size() >= 2, "应写入至少 2 条租户作用域回复 key: " + replyKeys);
        assertTrue(replyKeys.stream().anyMatch(k -> k.contains("T1:")), "应包含 T1 作用域 key");
        assertTrue(replyKeys.stream().anyMatch(k -> k.contains("T2:")), "应包含 T2 作用域 key");
        assertTrue(replyKeys.stream().allMatch(k -> k.contains(":0:")),
                "无版本治理时版本作用域应为 0: " + replyKeys);
    }

    @Test
    @DisplayName("功能隔离：租户 T2 读不到租户 T1 缓存的回复")
    void crossTenantReplyNotLeaked() {
        store = new ConcurrentHashMap<>();
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any());
        doAnswer(inv -> store.get(inv.getArgument(0))).when(valueOps).get(anyString());

        MDC.put("tenantId", "T1");
        cache.saveDecision(null, "北京天气", "location_weather", 0.9, 1L, "北京,天气");
        cache.saveReply("北京天气", "T1的私有回复", "location_weather", "北京,天气");

        // 同租户 T1 可读到自己的回复
        MDC.put("tenantId", "T1");
        var d1 = cache.getCachedDecision("北京天气");
        assertNotNull(d1, "T1 应命中决策");
        assertNotNull(d1.reply, "T1 应读到自己的回复");
        assertTrue(d1.reply.contains("T1"), "T1 回复内容应正确");

        // 换租户 T2：决策可共享（路由稳定），但回复不可见（跨租户隔离）
        MDC.put("tenantId", "T2");
        var d2 = cache.getCachedDecision("北京天气");
        assertNotNull(d2, "路由决策应跨租户共享");
        assertNull(d2.reply, "租户 T2 不应读到租户 T1 的私有回复（防跨租户泄露）");
    }

    // ==================== 2. 版本进入 key：知识更新后旧回复失效 ====================

    @Test
    @DisplayName("回复缓存 key 含索引版本：版本递增后旧 key 失效")
    void replyKeyScopedByVersion() {
        lenient().when(cacheVersionManager.getCurrentVersion()).thenReturn(7L);
        MDC.put("tenantId", "T1");
        cache.saveReply("北京天气", "v7回复", "location_weather", "北京,天气");

        var captor7 = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps, atLeastOnce()).set(captor7.capture(), anyString(), anyLong(), any());
        String keyV7 = captor7.getAllValues().stream()
                .filter(k -> k.startsWith("a2a:route:reply:") && k.contains(":7:"))
                .findFirst().orElse(null);
        assertNotNull(keyV7, "应生成版本 7 的 key");

        // 版本 8 → 不同 key（旧 v7 回复自然失效）
        lenient().when(cacheVersionManager.getCurrentVersion()).thenReturn(8L);
        cache.saveReply("北京天气", "v8回复", "location_weather", "北京,天气");

        var captor8 = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps, atLeastOnce()).set(captor8.capture(), anyString(), anyLong(), any());
        String keyV8 = captor8.getAllValues().stream()
                .filter(k -> k.startsWith("a2a:route:reply:") && k.contains(":8:"))
                .findFirst().orElse(null);
        assertNotNull(keyV8, "应生成版本 8 的新 key");
        assertFalse(keyV7.equals(keyV8), "版本递增后 key 应变化（旧回复失效）");
    }

    // ==================== 3. 回复缓存总开关 ====================

    @Test
    @DisplayName("reply-cache-enabled=false 时任何最终回复都不写入缓存")
    void replyCacheDisabledBySwitch() {
        ReflectionTestUtils.setField(cache, "replyCacheEnabled", false);
        MDC.put("tenantId", "T1");
        cache.saveReply("北京天气", "不应被缓存", "location_weather", "北京,天气");

        verify(valueOps, never()).set(startsWith("a2a:route:reply:"), anyString(), anyLong(), any());
    }
}
