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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetaQuestionConversationTest {

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
        lenient().when(redisTemplate.delete(anyString())).thenReturn(true);
        cache = new SemanticRouteCacheService(chatClientBuilder, redisTemplate, tokenizer,
                agentDiscoveryService, tfEmbedding, vectorCache, bgeEmbedding);
        ReflectionTestUtils.setField(cache, "cacheEnabled", true);
        lenient().when(agentDiscoveryService.getAgentTtl(anyString())).thenReturn(7200L);
        lenient().when(tokenizer.tokenize(anyString())).thenReturn(new LinkedHashSet<>(Arrays.asList("test")));
        lenient().when(valueOps.get(contains("intent:global:count:"))).thenReturn("2");
    }

    @Test @DisplayName("天气→再说一遍: 复述类跳过缓存")
    void weatherThenRepeat() {
        cache.saveReply("北京天气", "晴天22度", "location_weather", "天气");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", "再说一遍"));
        cache.saveReply("再说一遍", "晴天22度", "location_weather", "天气");
        System.out.println("  PASS: \u590d\u8ff0\u7c7b\u201c\u518d\u8bf4\u4e00\u904d\u201d\u8df3\u8fc7\u7f13\u5b58");
    }

    @Test @DisplayName("景点→你刚才说了什么: 复述类跳过缓存")
    void attractionThenWhatSaid() {
        cache.saveReply("故宫门票", "60元", "location_weather", "景点");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", "你刚才说了什么"));
        cache.saveReply("你刚才说了什么", "60元", "location_weather", "景点");
        System.out.println("  PASS");
    }

    @Test @DisplayName("多轮对话中复述不污染缓存")
    void multiTurnMeta() {
        cache.saveReply("推荐川菜", "推荐眉州东坡", "food_recommendation", "美食");
        cache.saveReply("人均多少", "80-100元", "food_recommendation", "美食查询");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", "把你刚才的回答再讲一遍"));
        cache.saveReply("把你刚才的回答再讲一遍", "80-100元", "food_recommendation", "复述");
        cache.saveReply("有停车位吗", "门口有车位", "food_recommendation", "美食查询");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", "我刚刚问了什么"));
        cache.saveReply("我刚刚问了什么", "门口有车位", "food_recommendation", "复述");
        System.out.println("  PASS: \u591a\u8f6e\u590d\u8ff0\u5747\u8df3\u8fc7\u7f13\u5b58\uff0c\u4e0a\u4e0b\u6587\u65e0\u6c61\u67d3");
    }

    @Test @DisplayName("跨用户复述互不干扰")
    void crossUserMeta() {
        cache.saveReply("北京天气", "晴天", "location_weather", "天气");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", "再说一遍"));
        cache.saveReply("再说一遍", "晴天", "location_weather", "复述");
        cache.saveReply("推荐火锅", "推荐海底捞", "food_recommendation", "美食");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", "再说一遍"));
        cache.saveReply("再说一遍", "推荐海底捞人", "food_recommendation", "复述");
        System.out.println("  PASS: \u8de8\u7528\u6237\u590d\u8ff0\u4e0d\u4e92\u76f8\u5e72\u6270");
    }

    @Test @DisplayName("5种复述表述均跳过缓存")
    void compoundMeta() {
        cache.saveReply("西湖有什么好玩", "推荐西湖十景", "location_weather", "景点");
        String[][] ms = {{"再讲一遍你刚才的推荐","推荐西湖十景"},{"重复一下","推荐西湖十景"},{"前一句的内容是什么","推荐西湖十景"},{"刚刚说的那个再讲一次","推荐西湖十景"},{"上一句说的是什么","推荐西湖十景"}};
        for (String[] m : ms) {
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", m[0]));
            cache.saveReply(m[0], m[1], "location_weather", "复述");
        }
        System.out.println("  PASS: 5\u79cd\u590d\u8ff0\u8868\u8ff0\u5747\u8df3\u8fc7\u7f13\u5b58");
    }
}
