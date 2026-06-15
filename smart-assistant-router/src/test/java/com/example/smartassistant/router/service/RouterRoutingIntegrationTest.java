package com.example.smartassistant.router.service;

import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import com.example.smartassistant.router.service.cache.SemanticRouteCacheService;
import com.example.smartassistant.router.model.ReflectionResult;
import com.example.smartassistant.router.service.core.TaskPlannerService;
import com.example.smartassistant.router.service.core.ResultMerger;
import com.example.smartassistant.router.service.core.ModelRoutingService;
import com.example.smartassistant.router.service.core.ReflectionService;
import com.example.smartassistant.router.service.core.RouterService;
import com.example.smartassistant.router.service.rag.RouterRagService;
import com.example.smartassistant.router.service.cache.BgeOnnxEmbeddingService;
import com.example.smartassistant.router.service.cache.TfEmbeddingService;
import com.example.smartassistant.router.service.cache.VectorCacheStore;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouterRoutingIntegrationTest {

    @Mock private AgentCallerService agentCallerService;
    @Mock private AgentDiscoveryService agentDiscoveryService;
    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec responseSpec;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RouterRagService ragService;
    @Mock private ChineseTokenizer tokenizer;
    @Mock private TaskPlannerService taskPlanner;
    @Mock private ResultMerger resultMerger;
    @Mock private ReflectionService reflectionService;
    @Mock private ModelRoutingService modelRoutingService;
    @Mock private ValueOperations<String, String> valueOps;
    private TfEmbeddingService tfEmbedding;
    private VectorCacheStore vectorCache;
    private BgeOnnxEmbeddingService bgeEmbedding;

    private SemanticRouteCacheService cacheService;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(responseSpec);
        lenient().when(responseSpec.content()).thenReturn("fallback reply");

        tfEmbedding = new TfEmbeddingService(tokenizer);
        vectorCache = new VectorCacheStore();
        bgeEmbedding = new BgeOnnxEmbeddingService();
        cacheService = new SemanticRouteCacheService(chatClientBuilder, redisTemplate, tokenizer,
                agentDiscoveryService, tfEmbedding, vectorCache, bgeEmbedding);
        ReflectionTestUtils.setField(cacheService, "cacheEnabled", true);
        // ⭐ 反思器默认通过（测试环境下不触发反思逻辑）
        lenient().when(reflectionService.evaluate(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new ReflectionResult(true, 1.0, "test.pass"));
        lenient().when(reflectionService.retry(anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn("retry result");
        new RouterService(agentCallerService, chatClientBuilder,
                Runnable::run, redisTemplate, ragService, cacheService, taskPlanner, resultMerger, reflectionService,
                modelRoutingService);
    }

    @Test
    @DisplayName("保存决策后精确映射和关键词映射已持久化")
    void testSaveDecisionPersistsMappings() {
        when(tokenizer.tokenize(anyString())).thenReturn(Set.of("上海", "天气"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        cacheService.saveDecision("r1", "上海天气", "location_weather", 0.5, 1L, "天气查询");

        verify(valueOps).set(startsWith("a2a:route:exact:"), anyString(), anyLong(), any());
        verify(valueOps).set(startsWith("a2a:route:keyword:"), anyString(), anyLong(), any());
        verify(valueOps).set(startsWith("a2a:route:semantic:"), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("精确匹配：缓存存在时返回正确决策")
    void testExactMatchReturnsDecision() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("a2a:route:exact:" + md5("上海天气"))).thenReturn("天气查询");
        when(valueOps.get("a2a:route:semantic:" + md5("天气查询"))).thenReturn(
                "{\"intentTag\":\"天气查询\",\"agentName\":\"location_weather\",\"confidence\":0.5," +
                "\"originalQuestion\":\"上海天气\",\"cachedAt\":1000,\"hitCount\":2,\"firstCachedAt\":1000}");
        when(valueOps.get("a2a:route:reply:" + md5("天气查询"))).thenReturn(
                "{\"reply\":\"上海今天晴\",\"agentName\":\"location_weather\"," +
                "\"originalQuestion\":\"上海天气\",\"firstCachedAt\":1000,\"hitCount\":2}");

        var d = cacheService.getCachedDecision("上海天气");
        assertNotNull(d);
        assertEquals("location_weather", d.agentName);
        assertEquals("上海今天晴", d.reply);
    }

    @Test
    @DisplayName("关键词匹配：精确未命中时返回路由决策")
    void testKeywordMatch() {
        String q = "上海天气如何";
        String kwHash = md5("上海,天气");
        String tagHash = md5("天气查询");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("a2a:route:exact:" + md5(q))).thenReturn(null);
        // 关键词路由 key 返回 intentTag
        when(valueOps.get("a2a:route:keyword:" + kwHash)).thenReturn("天气查询");
        // 关键词回复 key 为空（无回复缓存）
        when(valueOps.get("a2a:route:keyword:reply:" + kwHash)).thenReturn(null);
        // getByIntentTag 需要的语义决策
        when(valueOps.get("a2a:route:semantic:" + tagHash)).thenReturn(
                "{\"intentTag\":\"天气查询\",\"agentName\":\"location_weather\",\"confidence\":0.5," +
                "\"originalQuestion\":\"上海天气如何\",\"cachedAt\":1000,\"hitCount\":1,\"firstCachedAt\":1000}");
        when(tokenizer.tokenize(q)).thenReturn(Set.of("上海", "天气"));

        var d = cacheService.getCachedDecision(q);
        assertNotNull(d, "关键词匹配应返回决策");
    }

    @Test
    @DisplayName("同用户精确匹配用个性化前缀，不同表述用中性前缀")
    void testWrapCachedReplyUserAware() {
        var d = new SemanticRouteCacheService.CachedRouteDecision("tag", "agent", 0.5, "上海天气");
        d.reply = "天气很好";
        d.hitCount = 2;
        d.firstCachedAt = System.currentTimeMillis() - 1000;
        d.firstUserId = 1L;

        // 同用户 + 相同表述 → 个性化前缀
        String same = cacheService.wrapCachedReply("天气很好", d, "上海天气", 1L);
        assertTrue(same.contains("再帮你查一次") || same.contains("跟上次查询") || same.contains("还是同样的结果"),
                "同用户+相同表述应使用个性化前缀: " + same);

        // 不同用户 → 中性前缀
        String diff = cacheService.wrapCachedReply("天气很好", d, "上海天气", 2L);
        assertTrue(diff.contains("查询结果如下") || diff.contains("以下是相关信息") || diff.contains("这是查询到的结果"),
                "不同用户应使用中性前缀: " + diff);

        // 同用户 + 不同表述（关键词匹配命中）→ 中性前缀，不应说"再帮你查一次"
        var d2 = new SemanticRouteCacheService.CachedRouteDecision("tag", "agent", 0.5, "上海天气怎么样");
        d2.reply = "天气很好";
        d2.hitCount = 2;
        d2.firstCachedAt = System.currentTimeMillis() - 1000;
        d2.firstUserId = 1L;

        String sameDiffQ = cacheService.wrapCachedReply("天气很好", d2, "上海天气如何", 1L);
        assertTrue(sameDiffQ.contains("查询结果如下") || sameDiffQ.contains("以下是相关信息") || sameDiffQ.contains("这是查询到的结果"),
                "同用户+不同表述应使用中性前缀而非个性化: " + sameDiffQ);
        assertFalse(sameDiffQ.contains("再帮你查"),
                "同用户+不同表述不应包含'再帮你查': " + sameDiffQ);
    }

    @Test
    @DisplayName("不同用户高命中次数使用中性前缀")
    void testNeutralPrefixForHighHitDifferentUser() {
        var d = new SemanticRouteCacheService.CachedRouteDecision("tag", "agent", 0.5, "上海天气");
        d.reply = "天气不错";
        d.hitCount = 5;
        d.firstCachedAt = System.currentTimeMillis() - 1000;
        d.firstUserId = 1L;

        String result = cacheService.wrapCachedReply("天气不错", d, "上海天气", 2L);
        assertFalse(result.contains("之前查") || result.contains("上次查询"),
                "不同用户不应包含个性化用语: " + result);
    }

    @Test
    @DisplayName("同用户不同表述 hitCount>=3 也不应使用'我'字前缀")
    void testDifferentPhrasingHighHit() {
        var d = new SemanticRouteCacheService.CachedRouteDecision("tag", "agent", 0.5, "上海天气怎么样");
        d.reply = "天气不错";
        d.hitCount = 5;
        d.firstCachedAt = System.currentTimeMillis() - 1000;
        d.firstUserId = 1L;

        String result = cacheService.wrapCachedReply("天气不错", d, "上海天气如何", 1L);
        // 同用户但不同表述，不应出现"我之前"等个性化用语
        assertFalse(result.contains("我之前"), "不同表述不应包含'我之前': " + result);
    }

    private String md5(String str) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            var sb = new StringBuilder();
            for (byte b : md.digest(str.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return str; }
    }
}
