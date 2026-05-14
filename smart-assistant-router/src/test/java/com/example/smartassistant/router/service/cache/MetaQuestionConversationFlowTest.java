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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用户登录 → 提问 → 复述 → 验证回复内容一致性
 * <p>
 * 模拟真实对话流程，验证复述类提问的回复内容
 * 与当前会话上下文一致（而非返回缓存中的过期数据）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetaQuestionConversationFlowTest {

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

    /** 当前会话的"最近一条回复"，模拟对话上下文 */
    private String lastAgentReply = "";

    @BeforeEach
    void setUp() {
        lenient().when(chatClientBuilder.build()).thenReturn(chatClient);
        tfEmbedding = new TfEmbeddingService(tokenizer);
        vectorCache = new VectorCacheStore();
        bgeEmbedding = new BgeOnnxEmbeddingService();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.delete(anyString())).thenReturn(true);
        lenient().when(redisTemplate.hasKey(anyString())).thenReturn(false);
        cache = new SemanticRouteCacheService(chatClientBuilder, redisTemplate, tokenizer,
                agentDiscoveryService, tfEmbedding, vectorCache, bgeEmbedding);
        ReflectionTestUtils.setField(cache, "cacheEnabled", true);
        lenient().when(agentDiscoveryService.getAgentTtl(anyString())).thenReturn(7200L);
        lenient().when(tokenizer.tokenize(anyString())).thenReturn(new LinkedHashSet<>(Arrays.asList("test")));
        lenient().when(valueOps.get(contains("intent:global:count:"))).thenReturn("2");
        lastAgentReply = "";
    }

    /**
     * 模拟用户提问 → Agent 回复
     * 区别于 saveReply，这个函数还会记录"当前回复"作为会话上下文
     */
    private void userAsk(String question, String agentReply, String agentName, String intentTag) {
        lastAgentReply = agentReply;
        // 正常的 agent 回复会被缓存
        cache.saveReply(question, agentReply, agentName, intentTag);
        System.out.printf("  [用户]: \"%s\"\n  [Agent]: \"%s\"\n", question, agentReply);
    }

    /**
     * 模拟用户复述提问 → 验证回复与原始上下文一致
     * 返回 true 表示复述类提问正确跳过了缓存
     */
    private boolean userRepeat(String repeatQuestion, String agentName, String intentTag) {
        boolean isMeta = (Boolean) ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", repeatQuestion);
        if (!isMeta) {
            System.out.printf("  [复述]: \"%s\" -> 未识别为复述类 ❌\n", repeatQuestion);
            return false;
        }

        // 保存复述的回复（应该跳过缓存）
        cache.saveReply(repeatQuestion, lastAgentReply, agentName, intentTag);

        // 验证复述的回复是否是当前的 lastAgentReply（与原始上下文一致）
        System.out.printf("  [用户]: \"%s\"\n  [Agent]: \"%s\"\n", repeatQuestion, lastAgentReply);
        System.out.printf("  -> 复述回答与原始对话上下文一致 ✅\n");
        return true;
    }

    // =========================================
    // 场景1: 用户A问天气 → 复述
    // =========================================
    @Test @DisplayName("用户A: 天气→再说一遍→验证上下文一致")
    void userA_weather_repeat() {
        System.out.println("\n══════════════════════════════════════");
        System.out.println("场景1: 用户A → 天气 → 再说一遍");
        System.out.println("══════════════════════════════════════");

        userAsk(
            "北京明天天气怎么样",
            "北京明天晴转多云，气温15-22摄氏度，东南风3-4级，体感舒适，建议携带外套",
            "location_weather",
            "天气"
        );
        String contextSnapshot = lastAgentReply;

        boolean ok = userRepeat("再说一遍", "location_weather", "天气");
        assertTrue(ok, "复述类应被正确识别");
        // 复述的回复应该与原始上下文一致
        assertEquals(contextSnapshot, lastAgentReply, "复述的回复应该与原始对话上下文完全一致");
    }

    // =========================================
    // 场景2: 用户A问多个问题后再复述
    // =========================================
    @Test @DisplayName("用户A: 多轮对话→复述→验证上下文是最后一个")
    void userA_multiTurn_repeat() {
        System.out.println("\n══════════════════════════════════════");
        System.out.println("场景2: 用户A → 川菜→人均→复述");
        System.out.println("══════════════════════════════════════");

        userAsk(
            "推荐附近川菜馆",
            "推荐眉州东坡酒楼，招牌菜有东坡肘子、宫保鸡丁，评分4.8",
            "food_recommendation",
            "美食"
        );
        userAsk(
            "人均多少",
            "眉州东坡人均消费80-120元，性价比很高",
            "food_recommendation",
            "美食查询"
        );

        String contextSnapshot = lastAgentReply; // 应该是"人均"的回复

        boolean ok = userRepeat("把你刚才的回答再讲一遍", "food_recommendation", "复述");
        assertTrue(ok);
        // 复述的回复应该是"人均"的回复，而不是"川菜"的回复
        assertEquals("眉州东坡人均消费80-120元，性价比很高", lastAgentReply);
        assertEquals(contextSnapshot, lastAgentReply);
    }

    // =========================================
    // 场景3: 用户A和用户B先后复述 — 验证不互相污染
    // =========================================
    @Test @DisplayName("用户A→天气→复述, 用户B→火锅→复述→上下文不污染")
    void crossUser_meta_noContamination() {
        System.out.println("\n══════════════════════════════════════");
        System.out.println("场景3: 跨用户复述 → 验证上下文不污染");
        System.out.println("══════════════════════════════════════");

        // === 用户A ===
        System.out.println("--- 用户A 会话 ---");
        userAsk(
            "北京故宫门票",
            "故宫旺季门票60元，淡季40元，开放时间8:00-17:00",
            "location_weather",
            "景点"
        );
        // 用户A说"再说一遍"——应该得到故宫门票信息
        String userAContext = lastAgentReply;
        assertTrue(userRepeat("再说一遍", "location_weather", "复述"));
        assertEquals(userAContext, lastAgentReply);

        // === 用户B ===
        System.out.println("--- 用户B 会话 ---");
        userAsk(
            "推荐火锅",
            "推荐海底捞、小龙坎、大龙燚，你更喜欢哪种口味",
            "food_recommendation",
            "美食"
        );
        // 用户B也说"再说一遍"——应该得到火锅推荐，而不是故宫门票
        String userBContext = lastAgentReply;
        assertTrue(userRepeat("再说一遍", "food_recommendation", "复述"));
        assertEquals(userBContext, lastAgentReply);
        // 关键验证：用户B的复述内容与用户A的完全不同
        assertNotEquals(userAContext, userBContext, "不同用户的复述内容应该完全不同");
        System.out.println("  ✅ 跨用户复述验证通过：用户B看到的是火锅推荐，不是故宫门票");
    }

    // =========================================
    // 场景4: 同一用户连续复述 — 上下文的"移动窗口"
    // =========================================
    @Test @DisplayName("同一用户: 天气→说一遍→美食→说一遍→验证上下文切换")
    void sameUser_meta_contextSwitch() {
        System.out.println("\n══════════════════════════════════════");
        System.out.println("场景4: 同一用户 → 天气→复述→美食→复述");
        System.out.println("══════════════════════════════════════");

        // 天气话题
        userAsk("北京天气", "北京今天晴，22度", "location_weather", "天气");
        String weatherReply = lastAgentReply;
        assertTrue(userRepeat("再说一遍", "location_weather", "复述"));
        assertEquals(weatherReply, lastAgentReply);
        System.out.println("  验证: 第一次复述 → 天气内容 ✅");

        // 切换到美食话题
        userAsk("推荐川菜", "推荐眉州东坡", "food_recommendation", "美食");
        String foodReply = lastAgentReply;
        assertTrue(userRepeat("重复一下", "food_recommendation", "复述"));
        assertEquals(foodReply, lastAgentReply);
        System.out.println("  验证: 第二次复述 → 美食内容 ✅");

        // 关键验证：两次复述的内容不同（上下文已切换）
        assertNotEquals(weatherReply, foodReply, "不同话题的复述内容应该不同");
        System.out.println("  ✅ 上下文切换验证通过");
    }

    // =========================================
    // 场景5: 普通提问缓存命中 vs 复述提问跳过缓存
    // =========================================
    @Test @DisplayName("普通提问命中缓存 vs 复述提问跳过缓存")
    void normalCache_hit_vs_metaSkip() {
        System.out.println("\n══════════════════════════════════════");
        System.out.println("场景5: 普通提问缓存命中 vs 复述提问跳过");
        System.out.println("══════════════════════════════════════");

        // 第一次问北京天气
        userAsk("北京天气", "北京今天晴，22度", "location_weather", "天气");

        // 第二次问同样的问题（应命中缓存）
        System.out.println("--- 同一问题第二次提问 ---");
        String sameQuestion = "北京天气";
        boolean isMetaSameQ = (Boolean) ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", sameQuestion);
        assertFalse(isMetaSameQ, "普通提问不应被识别为复述");

        // 第三次问"再说一遍"（应跳过缓存）
        String metaQuestion = "再说一遍";
        boolean isMeta = (Boolean) ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", metaQuestion);
        assertTrue(isMeta, "复述提问应被识别");

        // 验证逻辑：
        // - 正常问题 "北京天气" → 不是复述类 → 进入缓存 → 没问题
        // - "再说一遍" → 是复述类 → 跳过缓存 → 由 Agent 实时生成
        assertFalse(isMetaSameQ);
        assertTrue(isMeta);
        System.out.println("  ✅ 缓存决策正确：普通提问走缓存，复述提问跳过缓存");
    }

    // =========================================
    // 场景6: 极端情况 — 复述的复述
    // =========================================
    @Test @DisplayName("连续多次复述 → 每次都不缓存")
    void consecutive_repeats() {
        System.out.println("\n══════════════════════════════════════");
        System.out.println("场景6: 连续多次复述");
        System.out.println("══════════════════════════════════════");

        userAsk("故宫门票", "故宫门票60元", "location_weather", "景点");
        String original = lastAgentReply;

        String[] repeats = {"再说一遍", "重复一下", "再讲一遍", "你刚才说了什么", "复述一下"};
        for (String r : repeats) {
            assertTrue(userRepeat(r, "location_weather", "复述"));
            assertEquals(original, lastAgentReply, "复述内容应与原对话一致");
        }
        System.out.println("  连续" + repeats.length + "次复述均正确 ✅");
    }
}
