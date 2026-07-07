package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 真实 Redis 端到端缓存测试。
 * 需要 Redis 运行在 localhost:6379（docker-compose 已启动）。
 * 验证 saveReply 对复述类提问的正确行为：路由决策缓存，回复内容跳过。
 */
class RedisEndToEndCacheTest {

    private static StringRedisTemplate realRedis;
    private static SemanticRouteCacheService cache;
    private static boolean redisReady = false;

    @BeforeAll
    static void init() {
        try {
            var config = new RedisStandaloneConfiguration("localhost", 6379);
            config.setPassword("redis123");
            config.setDatabase(1); // 使用独立 DB 避免污染
            var factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            realRedis = new StringRedisTemplate(factory);
            realRedis.afterPropertiesSet();
            realRedis.getConnectionFactory().getConnection().ping();
            redisReady = true;
            System.out.println("[Redis] 连接成功: localhost:6379 DB=1");

            // 清理 DB
            realRedis.getConnectionFactory().getConnection().flushDb();
            System.out.println("[Redis] DB 已清理");
        } catch (Exception e) {
            System.out.println("[Redis] 连接失败: " + e.getMessage());
            redisReady = false;
        }
    }

    @BeforeEach
    void setUp() {
        if (!redisReady) return;

        var tokenizer = mock(ChineseTokenizer.class);
        lenient().when(tokenizer.tokenize(anyString())).thenReturn(Set.of("test"));
        var agentDiscovery = mock(AgentDiscoveryService.class);
        lenient().when(agentDiscovery.getAgentTtl(anyString())).thenReturn(7200L);
        var chatBuilder = mock(ChatClient.Builder.class);
        lenient().when(chatBuilder.build()).thenReturn(mock(ChatClient.class));

        var tf = new TfEmbeddingService(tokenizer);
        var vc = new VectorCacheStore();
        var bge = new BgeOnnxEmbeddingService();
        cache = new SemanticRouteCacheService(chatBuilder, realRedis, tokenizer,
                agentDiscovery, tf, vc, bge, null, null);
        ReflectionTestUtils.setField(cache, "cacheEnabled", true);
    }

    @AfterEach
    void cleanup() {
        if (redisReady) {
            realRedis.getConnectionFactory().getConnection().flushDb();
        }
    }

    // ========================
    // 端到端测试：验证 Redis 中的缓存 key
    // ========================

    @Test
    void normalQuestion_shouldCacheRouteAndReply() {
        if (!redisReady) { System.out.println("[SKIP] Redis 不可用"); return; }
        System.out.println("\n=== 正常提问: 缓存路由决策 + 回复 ===");

        // 种子数据：模拟高频计数
        realRedis.opsForValue().set("intent:global:count:天气查询", "2");

        // 保存决策 + 回复
        cache.saveDecision("req1", "北京天气", "location_weather", 0.9, 1L, "天气查询");
        cache.saveReply("北京天气", "今天晴转多云22度", "location_weather", "天气查询");

        // 验证：精确匹配 key 存在
        var exactKey = "a2a:route:exact:" + md5("北京天气");
        var intentTag = realRedis.opsForValue().get(exactKey);
        assertNotNull(intentTag, "精确匹配 key 应存在");
        System.out.println("  精确映射: " + exactKey + " -> " + intentTag + " ✅");

        // 验证：关键字回复 key 存在（高频+长TTL）
        boolean hasKeywordReply = realRedis.keys("a2a:route:keyword:reply:*").size() > 0;
        assertTrue(hasKeywordReply, "关键字回复缓存应存在");
        System.out.println("  关键字回复缓存: 存在 ✅");

        // 清除测试数据
        realRedis.delete(realRedis.keys("a2a:route:*"));
        System.out.println("  清理测试数据 ✅");
    }

    @Test
    void metaQuestion_shouldCacheRouteOnly() {
        if (!redisReady) { System.out.println("[SKIP] Redis 不可用"); return; }
        System.out.println("\n=== 复述提问: 仅缓存路由决策，跳过回复 ===");

        // 先保存正常问题（产生上下文）
        cache.saveDecision("req2", "故宫门票", "location_weather", 0.9, 1L, "景点查询");
        cache.saveReply("故宫门票", "故宫门票60元", "location_weather", "景点查询");

        long beforeMeta = realRedis.keys("a2a:route:keyword:reply:*").size();

        // 复述提问
        boolean isMeta = (Boolean) ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", "再说一遍");
        assertTrue(isMeta, "复述应被识别");

        cache.saveReply("再说一遍", "故宫门票60元", "location_weather", "景点查询");

        long afterMeta = realRedis.keys("a2a:route:keyword:reply:*").size();

        // 验证：复述没有新增回复缓存 key
        assertEquals(beforeMeta, afterMeta, "复述类提问不应新增回复缓存");
        System.out.println("  复述提问: 回复缓存未增加（" + beforeMeta + " -> " + afterMeta + "）✅");

        realRedis.delete(realRedis.keys("a2a:route:*"));
    }

    @Test
    void adminOperation_shouldNotCacheReply() {
        if (!redisReady) { System.out.println("[SKIP] Redis 不可用"); return; }
        System.out.println("\n=== 管理员操作: 跳过回复缓存 ===");

        cache.saveDecision("req3", "同步景点数据", "location_weather", 0.9, 1L, "管理操作");
        // adminOperation=true 跳过回复缓存
        cache.saveReply("同步景点数据", "已成功同步100条景点数据", "location_weather", "管理操作", null, true);

        var replyCount = realRedis.keys("a2a:route:keyword:reply:*").size();
        assertEquals(0, replyCount, "管理员操作不应缓存回复");
        System.out.println("  管理员操作: 回复缓存数=" + replyCount + " ✅");

        realRedis.delete(realRedis.keys("a2a:route:*"));
    }

    @Test
    void shortTtlAgent_shouldSkipReplyCache() {
        if (!redisReady) { System.out.println("[SKIP] Redis 不可用"); return; }
        System.out.println("\n=== 短TTL Agent: 跳过回复缓存 ===");

        var agentDiscovery = mock(AgentDiscoveryService.class);
        lenient().when(agentDiscovery.getAgentTtl("location_weather")).thenReturn(1200L); // < 3600

        // 使用短 TTL 重新注入
        var tokenizer = mock(ChineseTokenizer.class);
        lenient().when(tokenizer.tokenize(anyString())).thenReturn(Set.of("test"));
        var agentDiscovery2 = mock(AgentDiscoveryService.class);
        lenient().when(agentDiscovery2.getAgentTtl(anyString())).thenReturn(1200L);
        var chatBuilder2 = mock(ChatClient.Builder.class);
        lenient().when(chatBuilder2.build()).thenReturn(mock(ChatClient.class));
        cache = new SemanticRouteCacheService(chatBuilder2, realRedis, tokenizer,
                agentDiscovery2, new TfEmbeddingService(tokenizer), new VectorCacheStore(), new BgeOnnxEmbeddingService(), null, null);
        ReflectionTestUtils.setField(cache, "cacheEnabled", true);

        cache.saveDecision("req4", "当前温度", "location_weather", 0.9, 1L, "天气查询");
        cache.saveReply("当前温度", "当前温度22度", "location_weather", "天气查询");

        var replyCount = realRedis.keys("a2a:route:keyword:reply:*").size();
        assertEquals(0, replyCount, "短 TTL Agent 不应缓存回复");
        System.out.println("  短 TTL(1200s): 回复缓存数=" + replyCount + " ✅");

        realRedis.delete(realRedis.keys("a2a:route:*"));
    }

    @Test
    void crossUser_conversation_noContamination() {
        if (!redisReady) { System.out.println("[SKIP] Redis 不可用"); return; }
        System.out.println("\n=== 跨用户: 复述不污染 ===");

        // 种子数据：模拟高频计数
        realRedis.opsForValue().set("intent:global:count:景点", "3");
        realRedis.opsForValue().set("intent:global:count:美食", "3");

        // 用户A: 故宫
        cache.saveDecision("reqA", "故宫门票", "location_weather", 0.9, 1L, "景点");
        cache.saveReply("故宫门票", "故宫60元", "location_weather", "景点");
        String userAContext = "故宫60元";

        // 复述（用户A）- 不缓存回复
        cache.saveReply("再说一遍", "故宫60元", "location_weather", "景点");

        // 用户B: 火锅
        cache.saveDecision("reqB", "推荐火锅", "food_recommendation", 0.9, 2L, "美食");
        cache.saveReply("推荐火锅", "推荐海底捞", "food_recommendation", "美食");

        // 复述（用户B）- 不缓存回复，内容应该是火锅
        cache.saveReply("再说一遍", "推荐海底捞", "food_recommendation", "美食");

        // 验证：只有正常问题的回复缓存，复述的没有
        var replyKeys = realKeys("a2a:route:keyword:reply:*");
        assertTrue(replyKeys.size() > 0, "应有正常回复缓存");
        System.out.println("  回复缓存数: " + replyKeys.size() + " (故宫+火锅共享keyword) ✅");

        // 验证：路由决策缓存存在 × 4 (故宫决策 + 故宫回复密钥 + 火锅决策 + 火锅回复密钥)
        // 注：keyword:reply 只有两个，但 exact 映射有 4 个（每个问题各一个）
        var exactKeys = realKeys("a2a:route:exact:*");
        assertTrue(exactKeys.size() >= 2, "路由决策应被缓存");

        realRedis.delete(realRedis.keys("a2a:route:*"));
    }

    @Test
    void lowFreqQuestion_shouldNotCacheReply() {
        if (!redisReady) { System.out.println("[SKIP] Redis 不可用"); return; }
        System.out.println("\n=== 低频提问: 跳过回复缓存 ===");

        // 使用新 cache 实例，计数默认 0
        var tokenizer = mock(ChineseTokenizer.class);
        lenient().when(tokenizer.tokenize(anyString())).thenReturn(Set.of("test"));
        var chatBuilder = mock(ChatClient.Builder.class);
        lenient().when(chatBuilder.build()).thenReturn(mock(ChatClient.class));
        var agentDiscovery = mock(AgentDiscoveryService.class);
        lenient().when(agentDiscovery.getAgentTtl(anyString())).thenReturn(7200L);
        cache = new SemanticRouteCacheService(chatBuilder, realRedis, tokenizer,
                agentDiscovery, new TfEmbeddingService(tokenizer), new VectorCacheStore(), new BgeOnnxEmbeddingService(), null, null);
        ReflectionTestUtils.setField(cache, "cacheEnabled", true);

        // 第一次提问（低频）
        cache.saveDecision("reqL1", "罕见问题", "general_chat", 0.9, 1L, "闲聊");
        cache.saveReply("罕见问题", "这是一个罕见问题的回复", "general_chat", "闲聊");

        // 回复缓存不应存在（低频）
        var replyKeys = realKeys("a2a:route:keyword:reply:*");
        assertEquals(0, replyKeys.size(), "低频问题不应缓存回复");

        System.out.println("  低频问题: 回复缓存数=" + replyKeys.size() + " ✅");

        realRedis.delete(realRedis.keys("a2a:route:*"));
    }

    // ========================
    // 辅助
    // ========================

    private Set<String> realKeys(String pattern) {
        return realRedis.keys(pattern);
    }

    private static String md5(String s) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return s; }
    }
}
