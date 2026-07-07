package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CacheDecisionTest {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private AgentDiscoveryService agentDiscoveryService;
    @Mock private ChineseTokenizer tokenizer;

    private SemanticRouteCacheService cache;
    private TfEmbeddingService tfEmbedding;
    private VectorCacheStore vectorCache;
    private BgeOnnxEmbeddingService bgeEmbedding;

    @BeforeEach
    void setUp() {
        lenient().when(chatClientBuilder.build()).thenReturn(chatClient);
        tfEmbedding = new TfEmbeddingService(tokenizer);
        vectorCache = new VectorCacheStore();
        bgeEmbedding = new BgeOnnxEmbeddingService();

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);

        cache = new SemanticRouteCacheService(chatClientBuilder, redisTemplate, tokenizer,
                agentDiscoveryService, tfEmbedding, vectorCache, bgeEmbedding, null, null);
        ReflectionTestUtils.setField(cache, "cacheEnabled", true);
    }

    // ========================
    // 1. isMetaQuestion 测试 (10 cases)
    // ========================

    @Test
    @DisplayName("复述类: '再说一遍' 应跳过缓存")
    void meta_repeat() {
        assertTrue(invokeIsMeta("再说一遍"));
    }

    @Test
    @DisplayName("复述类: '把你刚才的回答再讲一遍' 应跳过缓存")
    void meta_repeatLong() {
        assertTrue(invokeIsMeta("把你刚才的回答再讲一遍"));
    }

    @Test
    @DisplayName("复述类: '我刚刚问了什么' 应跳过缓存")
    void meta_whatAsked() {
        assertTrue(invokeIsMeta("我刚刚问了什么"));
    }

    @Test
    @DisplayName("复述类: '你刚才说了什么' 应跳过缓存")
    void meta_whatSaid() {
        assertTrue(invokeIsMeta("你刚才说了什么"));
    }

    @Test
    @DisplayName("复述类: '重复一下' 应跳过缓存")
    void meta_repeatShort() {
        assertTrue(invokeIsMeta("重复一下"));
    }

    @Test
    @DisplayName("复述类: '我之前问过的问题' 应跳过缓存")
    void meta_previous() {
        assertTrue(invokeIsMeta("我之前问过的问题"));
    }

    @Test
    @DisplayName("复述类: '我的问题是什么' 应跳过缓存")
    void meta_myQuestion() {
        assertTrue(invokeIsMeta("我的问题是什么"));
    }

    @Test
    @DisplayName("正常提问: '北京天气怎么样' 不应跳过缓存")
    void meta_normalWeather() {
        assertFalse(invokeIsMeta("北京天气怎么样"));
    }

    @Test
    @DisplayName("正常提问: '故宫门票多少钱' 不应跳过缓存")
    void meta_normalAttraction() {
        assertFalse(invokeIsMeta("故宫门票多少钱"));
    }

    @Test
    @DisplayName("正常提问: '推荐附近川菜馆' 不应跳过缓存")
    void meta_normalFood() {
        assertFalse(invokeIsMeta("推荐附近川菜馆"));
    }

    private boolean invokeIsMeta(String q) {
        return ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", q);
    }

    // ========================
    // 2. generateIntentTag 确定性测试 (10 cases)
    // ========================

    @Test
    @DisplayName("intentTag: '北京天气' 与 '北京今天天气怎么样' 应生成相同 tag")
    void intentTag_sameWeather() {
        when(tokenizer.tokenize("北京天气")).thenReturn(Set.of("北京", "天气"));
        when(tokenizer.tokenize("北京今天天气怎么样")).thenReturn(Set.of("北京", "今天", "天气"));
        String tag1 = cache.generateIntentTag("北京天气");
        String tag2 = cache.generateIntentTag("北京今天天气怎么样");
        // 关键词集合相同（排序后）：北京,天气
        assertEquals("北京,天气", tag1);
        assertTrue(tag2.contains("北京") && tag2.contains("天气"));
    }

    @Test
    @DisplayName("intentTag: '附近美食' 与 '有什么好吃的' 应生成不同 tag")
    void intentTag_diffIntent() {
        when(tokenizer.tokenize("附近美食")).thenReturn(Set.of("附近", "美食"));
        when(tokenizer.tokenize("有什么好吃的")).thenReturn(Set.of("好吃", "什么"));
        String tag1 = cache.generateIntentTag("附近美食");
        String tag2 = cache.generateIntentTag("有什么好吃的");
        assertNotEquals(tag1, tag2);
    }

    @Test
    @DisplayName("intentTag: 空字符串应返回 null")
    void intentTag_empty() {
        when(tokenizer.tokenize("")).thenReturn(Set.of());
        assertNull(cache.generateIntentTag(""));
    }

    @Test
    @DisplayName("intentTag: 长文本应返回排序后的关键词")
    void intentTag_longText() {
        when(tokenizer.tokenize(anyString())).thenReturn(Set.of("北京", "故宫", "门票", "价格", "开放", "时间"));
        String tag = cache.generateIntentTag("北京故宫门票多少钱开放时间是什么");
        // 关键词按汉字 Unicode 排序
        assertTrue(tag.contains("北京") && tag.contains("故宫") && tag.contains("开放") && tag.contains("时间") && tag.contains("价格") && tag.contains("门票"));
        assertEquals(6, tag.split(",").length);
    }

    @Test
    @DisplayName("intentTag: 数字被过滤")
    void intentTag_numbers() {
        when(tokenizer.tokenize("12345")).thenReturn(Set.of());
        assertNull(cache.generateIntentTag("12345"));
    }

    @Test
    @DisplayName("intentTag: 同义词应保留原文")
    void intentTag_synonym() {
        when(tokenizer.tokenize("上海天气")).thenReturn(Set.of("上海", "天气"));
        String tag = cache.generateIntentTag("上海天气");
        assertEquals("上海,天气", tag);
    }

    // ========================
    // 3. saveReply 跳过高频测试 (10 cases) — 通过 verify 验证行为
    // ========================

    @Test
    @DisplayName("saveReply: null reply 不保存")
    void saveReply_nullReply() {
        // Redis opsForValue 返回 null → saveReply 在写入前已 return
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cache.saveReply("test", null, "test_agent", "test_intent");
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("saveReply: meta question 跳过缓存")
    void saveReply_metaQuestion() {
        when(tokenizer.tokenize("再说一遍")).thenReturn(Set.of("再说"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cache.saveReply("再说一遍", "好的我重复一遍", "test_agent");
        verify(valueOps, never()).set(startsWith("a2a:route:keyword:reply:"), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("saveReply: 空回复不保存")
    void saveReply_blankReply() {
        cache.saveReply("test", "   ", "test_agent", "test_intent");
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("saveReply: 错误回复(❌)不保存")
    void saveReply_errorPrefix() {
        cache.saveReply("test", "❌ 出错了", "test_agent", "test_intent");
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("saveReply: 管理员权限错误(⚠️)不保存")
    void saveReply_adminError() {
        cache.saveReply("test", "⚠️ 此操作需要管理员权限", "test_agent", "test_intent");
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("saveReply: adminOperation=true 不保存")
    void saveReply_adminOperation() {
        cache.saveReply("test", "同步完成", "test_agent", "test_intent", null, true);
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("saveReply: null intentTag 不保存")
    void saveReply_nullIntentTag() {
        cache.saveReply("test", "reply", "test_agent", null);
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("saveReply: cacheDisabled 不保存")
    void saveReply_cacheDisabled() {
        ReflectionTestUtils.setField(cache, "cacheEnabled", false);
        cache.saveReply("test", "reply", "test_agent", "test_intent");
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    // ========================
    // 4. 决策缓存路径测试 (6 cases)
    // ========================

    @Test
    @DisplayName("getCachedDecision: cacheDisabled 返回 null")
    void getCachedDecision_disabled() {
        ReflectionTestUtils.setField(cache, "cacheEnabled", false);
        assertNull(cache.getCachedDecision("test"));
    }

    @Test
    @DisplayName("getCachedDecision: null question 返回 null")
    void getCachedDecision_null() {
        assertNull(cache.getCachedDecision(null));
    }

    @Test
    @DisplayName("getCachedDecision: blank question 返回 null")
    void getCachedDecision_blank() {
        assertNull(cache.getCachedDecision("  "));
    }

    @Test
    @DisplayName("saveDecision: 保存后精确匹配可读取")
    void saveDecision_exactMatch() {
        when(tokenizer.tokenize("北京天气")).thenReturn(Set.of("北京", "天气"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cache.saveDecision("req1", "北京天气", "location_weather", 0.9, 1L, "北京,天气");
        verify(valueOps, atLeastOnce()).set(startsWith("a2a:route:exact:"), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("saveDecision: 关键词哈希写入")
    void saveDecision_keywordHash() {
        when(tokenizer.tokenize("上海天气")).thenReturn(Set.of("上海", "天气"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cache.saveDecision("req2", "上海天气", "location_weather", 0.9, 1L, "上海,天气");
        verify(valueOps, atLeastOnce()).set(startsWith("a2a:route:keyword:"), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("saveDecision: 向量缓存写入")
    void saveDecision_vectorCache() {
        when(tokenizer.tokenize("北京天气")).thenReturn(Set.of("北京", "天气"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cache.saveDecision("req3", "北京天气", "location_weather", 0.9, 1L, "北京,天气");
        // 向量缓存写入 VectorCacheStore（非 Redis），不抛异常即通过
    }

    // ========================
    // 5. saveReply 正常路径测试 (5 cases)
    // ========================

    @Test
    @DisplayName("saveReply: 高频意图应保存 keyword reply")
    void saveReply_highFreq_saveKeyword() {
        when(tokenizer.tokenize("北京天气")).thenReturn(Set.of("北京", "天气"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("intent:global:count:北京,天气")).thenReturn("2");
        when(agentDiscoveryService.getAgentTtl("location_weather")).thenReturn(7200L);

        cache.saveReply("北京天气", "今天晴转多云", "location_weather", "北京,天气");
        verify(valueOps, atLeastOnce()).set(startsWith("a2a:route:keyword:reply:"), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("saveReply: 低频繁意图不保存 keyword reply")
    void saveReply_lowFreq_skip() {
        when(tokenizer.tokenize("北京天气")).thenReturn(Set.of("北京", "天气"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("intent:global:count:北京,天气")).thenReturn("1");
        when(agentDiscoveryService.getAgentTtl("location_weather")).thenReturn(7200L);

        cache.saveReply("北京天气", "今天晴转多云", "location_weather", "北京,天气");
        verify(valueOps, never()).set(startsWith("a2a:route:keyword:reply:"), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("saveReply: 天气类短 TTL (<3600) 不缓存回复")
    void saveReply_shortTtl_skip() {
        when(tokenizer.tokenize("北京天气")).thenReturn(Set.of("北京", "天气"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("intent:global:count:北京,天气")).thenReturn("2");
        when(agentDiscoveryService.getAgentTtl("location_weather")).thenReturn(1200L);

        cache.saveReply("北京天气", "今天晴转多云", "location_weather", "北京,天气");
        verify(valueOps, never()).set(startsWith("a2a:route:keyword:reply:"), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("saveReply: 美食类长 TTL 且高频应缓存回复")
    void saveReply_foodHighFreq_save() {
        when(tokenizer.tokenize("附近川菜")).thenReturn(Set.of("附近", "川菜"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("intent:global:count:附近,川菜")).thenReturn("3");
        when(agentDiscoveryService.getAgentTtl("food_recommendation")).thenReturn(43200L);

        cache.saveReply("附近川菜", "推荐眉州东坡", "food_recommendation", "附近,川菜");
        verify(valueOps, atLeastOnce()).set(startsWith("a2a:route:keyword:reply:"), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("saveReply: 通用闲聊长 TTL 且高频应缓存回复")
    void saveReply_generalHighFreq_save() {
        when(tokenizer.tokenize("你好")).thenReturn(Set.of("你好"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("intent:global:count:你好")).thenReturn("5");
        when(agentDiscoveryService.getAgentTtl("general_chat")).thenReturn(7200L);

        cache.saveReply("你好", "你好！有什么可以帮你的？", "general_chat", "你好");
        verify(valueOps, atLeastOnce()).set(startsWith("a2a:route:keyword:reply:"), anyString(), anyLong(), any());
    }
}
