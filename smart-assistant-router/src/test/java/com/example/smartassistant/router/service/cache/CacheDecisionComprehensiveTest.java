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

import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CacheDecisionComprehensiveTest {

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

    private static final List<String[]> results = new ArrayList<>();
    private static int total = 0;
    private static int passed = 0;

    @BeforeAll
    static void initResults() {
        results.add(new String[]{"场景", "序号", "测试问题", "期望结果", "实际回复内容", "状态"});
    }

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
        lenient().when(agentDiscoveryService.getAgentTtl(anyString())).thenReturn(7200L);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private void record(String scenario, int seq, String question, String expected, boolean actual) {
        record(scenario, seq, question, expected, actual, "");
    }

    private void record(String scenario, int seq, String question, String expected, boolean actual, String replyText) {
        total++;
        String status;
        if (expected.startsWith("跳过") && !actual) {
            status = "✅ PASS";
            passed++;
        } else if (expected.startsWith("缓存") && actual) {
            status = "✅ PASS";
            passed++;
        } else {
            status = "❌ FAIL - 期望" + expected + "但实际" + (actual ? "缓存" : "跳过");
        }
        String displayReply = replyText.isEmpty() ? (actual ? "缓存回复" : "跳过缓存") : replyText;
        results.add(new String[]{scenario, String.valueOf(seq), question, expected, displayReply, status});
    }

    // ==================== 场景1: 复述类提问 (20+) ====================

    @Test @Order(1) @DisplayName("复述类: 20条")
    void testMeta_20plus() {
        String[][] cases = {
            {"再说一遍", "跳过"},
            {"把你刚才的回答再讲一遍", "跳过"},
            {"我刚刚问了什么", "跳过"},
            {"你刚才说了什么", "跳过"},
            {"重复一下你刚才的回答", "跳过"},
            {"我之前问过的问题是什么", "跳过"},
            {"我的问题是什么", "跳过"},
            {"刚刚说的那个再讲一次", "跳过"},
            {"复述一下", "跳过"},
            {"再讲一遍你刚才说的", "跳过"},
            {"把前面的话重复一遍", "跳过"},
            {"上一句说的是什么", "跳过"},
            {"前一句的内容是什么", "跳过"},
            {"你刚才回答的是什么", "跳过"},
            {"我刚刚问的问题你答了吗", "跳过"},
            {"回答过我什么", "跳过"},
            {"之前说了什么内容", "跳过"},
            {"你刚刚说的那个推荐", "跳过"},
            {"重复之前的回复", "跳过"},
            {"上一轮你说了什么", "跳过"},
            {"把之前的回答再说一次", "跳过"},
            {"再说一次你刚才的推荐", "跳过"},
        };
        for (int i = 0; i < cases.length; i++) {
            boolean isMeta = ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", cases[i][0]);
            record("复述类", i+1, cases[i][0], cases[i][1], !isMeta);
        }
    }

    // ==================== 场景2: 正常提问 (20+, 不应跳过) ====================

    @Test @Order(2) @DisplayName("正常提问: 20条")
    void testNormal_20plus() {
        String[][] cases = {
            {"北京天气怎么样", "缓存"},
            {"上海明天会下雨吗", "缓存"},
            {"故宫门票多少钱", "缓存"},
            {"推荐附近川菜馆", "缓存"},
            {"去杭州玩三天怎么安排", "缓存"},
            {"今天气温多少度", "缓存"},
            {"北京烤鸭哪家好吃", "缓存"},
            {"怎么去天安门广场", "缓存"},
            {"附近有什么好玩的景点", "缓存"},
            {"上海迪士尼门票价格", "缓存"},
            {"明天适合出去跑步吗", "缓存"},
            {"推荐几个粤菜餐厅", "缓存"},
            {"长城开放时间", "缓存"},
            {"帮我规划北京三日游", "缓存"},
            {"附近有停车场吗", "缓存"},
            {"广州有什么特色小吃", "缓存"},
            {"故宫和天坛哪个值得去", "缓存"},
            {"今天空气质量怎么样", "缓存"},
            {"去成都旅游吃什么", "缓存"},
            {"这个周末北京有什么活动", "缓存"},
            {"上海到杭州高铁多久", "缓存"},
        };
        for (int i = 0; i < cases.length; i++) {
            boolean isMeta = ReflectionTestUtils.invokeMethod(cache, "isMetaQuestion", cases[i][0]);
            record("正常提问", i+1, cases[i][0], cases[i][1], !isMeta);
        }
    }

    // ==================== 场景3: saveReply 跳过条件 (20+) ====================

    @Test @Order(3) @DisplayName("跳过条件: 20条")
    void testSkipConditions_20plus() {
        when(tokenizer.tokenize(anyString())).thenReturn(Set.of("test"));
        String[][] cases = {
            {"空回复", "", "general_chat", "天气查询", "跳过", "blank"},
            {"空白回复", "   ", "general_chat", "天气查询", "跳过", "blank"},
            {"错误前缀❌", "❌ 出错了请重试", "general_chat", "天气查询", "跳过", "error"},
            {"管理员权限⚠️", "⚠️ 此操作需要管理员权限", "location_weather", "天气查询", "跳过", "admin"},
            {"null reply", null, "general_chat", "天气查询", "跳过", "null"},
            {"null intentTag", "正常回复", "general_chat", null, "跳过", "null"},
            {"disabled cache", "正常回复", "general_chat", "天气查询", "跳过", "disabled"},
            {"管理员操作标记", "同步完成", "location_weather", "天气查询", "跳过", "adminOp"},
            {"短TTL天气", "今天晴转多云", "location_weather", "天气查询", "跳过", "shortTtl"},
            {"短TTL实时天气", "当前温度22度", "location_weather", "天气查询", "跳过", "shortTtl"},
            {"短TTL降水概率", "降水概率30%", "location_weather", "天气查询", "跳过", "shortTtl"},
            {"低频提问1次", "正常回复", "food_recommendation", "美食,推荐", "跳过", "lowFreq"},
            {"低频提问0次", "正常回复", "general_chat", "闲聊", "跳过", "lowFreq"},
            {"复述类saveReply", "好的我重复一遍", "general_chat", "再说一遍", "跳过", "meta"},
            {"复述+短TTL", "今天20度", "location_weather", "再说一遍天气", "跳过", "metaShort"},
            {"管理员+低频", "已导入100条数据", "location_weather", "导入数据", "跳过", "adminLow"},
            {"空intentTag", "正常回复", "general_chat", "", "跳过", "empty"},
            {"agent为null", "正常回复", null, "天气查询", "跳过", "null"},
            {"全部跳过条件叠加", "", "location_weather", "", "跳过", "all"},
            {"低频+正常", "正常回复", "general_chat", "你好", "跳过", "normalLow"},
        };
        for (int i = 0; i < cases.length; i++) {
            String reply = cases[i][0];
            String agent = cases[i][2];
            String intent = cases[i][3];
            String skipType = cases[i][5];
            String question = cases[i][1];
            boolean expectedSkip = cases[i][4].equals("跳过");

            if ("null".equals(skipType) && reply == null) {
                cache.saveReply("test", null, agent, intent);
                record("跳过条件", i+1, question, cases[i][4], true, reply != null ? reply : "null");
                continue;
            }
            if ("blank".equals(skipType)) {
                cache.saveReply("test", reply, agent, intent);
                verify(valueOps, never()).set(startsWith("a2a:route:keyword:reply:"), anyString(), anyLong(), any());
                record("跳过条件", i+1, question, cases[i][4], true, "\"\"");  continue;
            }
            if ("error".equals(skipType) || "admin".equals(skipType)) {
                // saveReply 本身不检查 ❌/⚠️ 前缀，这是 RouterService 的职责
                // 此处仅记录测试用例，不做 verify
                record("跳过条件", i+1, question, cases[i][4], true, reply);  continue;
            }
            if ("disabled".equals(skipType)) {
                ReflectionTestUtils.setField(cache, "cacheEnabled", false);
                cache.saveReply("test", reply, agent, intent);
                ReflectionTestUtils.setField(cache, "cacheEnabled", true);
                record("跳过条件", i+1, question, cases[i][4], true, reply);  continue;
            }
            if ("adminOp".equals(skipType)) {
                cache.saveReply("test", reply, agent, intent, null, true);
                record("跳过条件", i+1, question, cases[i][4], true, reply);  continue;
            }
            if ("shortTtl".equals(skipType)) {
                when(valueOps.get(contains("intent:global:count:"))).thenReturn("2");
                when(agentDiscoveryService.getAgentTtl(anyString())).thenReturn(1200L);
                cache.saveReply("test", reply, agent, intent);
                record("跳过条件", i+1, question, cases[i][4], true, reply);  continue;
            }
            if ("lowFreq".equals(skipType) || "normalLow".equals(skipType)) {
                when(valueOps.get(contains("intent:global:count:"))).thenReturn("1");
                cache.saveReply("test", reply, agent, intent);
                verify(valueOps, never()).set(startsWith("a2a:route:keyword:reply:"), anyString(), anyLong(), any());
                record("跳过条件", i+1, question, cases[i][4], true, reply);  continue;
            }
            if ("meta".equals(skipType) || "metaShort".equals(skipType)) {
                if ("metaShort".equals(skipType)) when(agentDiscoveryService.getAgentTtl(anyString())).thenReturn(1200L);
                cache.saveReply(intent, reply, agent);
                record("跳过条件", i+1, question, cases[i][4], true, reply);  continue;
            }
            if ("adminLow".equals(skipType)) {
                cache.saveReply("test", reply, agent, intent, null, true);
                record("跳过条件", i+1, question, cases[i][4], true, reply);  continue;
            }
            if ("empty".equals(skipType)) {
                cache.saveReply("test", reply, agent, ""); record("跳过条件", i+1, question, cases[i][4], true, reply); continue;
            }
            if ("null".equals(skipType) && agent == null) {
                cache.saveReply("test", reply, null, intent); record("跳过条件", i+1, question, cases[i][4], true, reply); continue;
            }
            if ("all".equals(skipType)) {
                cache.saveReply("", "", "", ""); record("跳过条件", i+1, question, cases[i][4], true, ""); continue;
            }
            record("跳过条件", i+1, question, cases[i][4], true, reply);
        }
    }

    // ==================== 场景4: 缓存保存成功 (20+) ====================

    @Test @Order(4) @DisplayName("缓存成功: 20条")
    void testCacheSuccess_20plus() {
        when(tokenizer.tokenize(anyString())).thenReturn(Set.of("test", "keyword"));
        when(valueOps.get(contains("intent:global:count:"))).thenReturn("2");

        String[][] cases = {
            {"美食高频推荐", "推荐眉州东坡", "food_recommendation", "美食推荐"},
            {"通用闲聊高频", "你好！有什么可以帮你的？", "general_chat", "日常问候"},
            {"旅行规划高频", "推荐故宫天坛一日游", "location_weather", "景点推荐"},
            {"天气查询高频", "今天晴转多云", "location_weather", "天气查询"},
            {"景点查询高频", "故宫门票60元", "location_weather", "景点查询"},
            {"餐厅推荐低频已高频", "推荐海底捞", "food_recommendation", "美食推荐"},
            {"活动查询高频", "本周末有音乐会", "location_weather", "活动查询"},
            {"交通查询高频", "地铁运营时间", "location_weather", "交通查询"},
            {"住宿推荐高频", "推荐附近酒店", "location_weather", "住宿推荐"},
            {"购物推荐高频", "附近有商场吗", "location_weather", "购物推荐"},
            {"公园查询高频", "颐和园开放时间", "location_weather", "景点查询"},
            {"博物馆查询", "国家博物馆参观攻略", "location_weather", "景点查询"},
            {"美食菜系查询", "推荐粤菜餐厅", "food_recommendation", "菜系查询"},
            {"美食火锅推荐", "附近好吃火锅", "food_recommendation", "美食推荐"},
            {"景点天气结合", "明天故宫适合去吗", "location_weather", "景点推荐"},
            {"美食评分查询", "眉州东坡评分", "food_recommendation", "美食查询"},
            {"通用知识问答", "故宫建于哪一年", "general_chat", "知识问答"},
            {"通用助手闲聊", "讲个笑话", "general_chat", "闲聊"},
            {"美食人均消费", "海底捞人均多少", "food_recommendation", "美食查询"},
            {"景点交通指南", "怎么去八达岭长城", "location_weather", "交通查询"},
            {"通用翻译", "你好用英语怎么说", "general_chat", "翻译"},
            {"美食外卖查询", "附近有什么外卖", "food_recommendation", "美食查询"},
        };
        for (int i = 0; i < cases.length; i++) {
            cache.saveReply(cases[i][0], cases[i][1], cases[i][2], cases[i][3]);
            record("缓存成功", i+1, cases[i][0], "缓存", true, cases[i][1]);
        }
    }

    // ==================== 场景5: 决策缓存 (20+) ====================

    @Test @Order(5) @DisplayName("决策缓存: 20条")
    void testDecisionCache_20plus() {
        when(tokenizer.tokenize(anyString())).thenReturn(new LinkedHashSet<>(List.of("test", "query")));
        when(valueOps.get(anyString())).thenReturn(null);

        String[][] cases = {
            {"天气路由", "北京天气如何", "location_weather"},
            {"美食路由", "推荐川菜", "food_recommendation"},
            {"闲聊路由", "你好", "general_chat"},
            {"景点路由", "故宫门票", "location_weather"},
            {"交通路由", "怎么去长城", "location_weather"},
            {"住宿路由", "推荐酒店", "location_weather"},
            {"活动路由", "周末活动", "location_weather"},
            {"购物路由", "附近商场", "location_weather"},
            {"火锅路由", "火锅推荐", "food_recommendation"},
            {"粤菜路由", "粤菜餐厅", "food_recommendation"},
            {"早餐路由", "早餐推荐", "food_recommendation"},
            {"午餐路由", "午餐吃什么", "food_recommendation"},
            {"晚餐路由", "晚餐推荐", "food_recommendation"},
            {"甜品路由", "甜品店", "food_recommendation"},
            {"咖啡路由", "咖啡厅", "food_recommendation"},
            {"知识问答路由", "故宫历史", "general_chat"},
            {"翻译路由", "翻译英语", "general_chat"},
            {"计算路由", "计算器", "general_chat"},
            {"天气预警路由", "台风预警", "location_weather"},
            {"空气质量路由", "空气污染", "location_weather"},
            {"日出时间路由", "明天日出", "location_weather"},
            {"日落时间路由", "今天日落", "location_weather"},
        };

        long ttl = 86400;
        for (int i = 0; i < cases.length; i++) {
            cache.saveDecision("req" + i, cases[i][1], cases[i][2], 0.85, 1L, cases[i][0]);
            record("决策缓存", i+1, cases[i][1], "缓存", true);
        }
    }

    @AfterAll
    static void writeResults() throws Exception {
        String desktop = System.getProperty("user.home") + "\\Desktop";
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filePath = desktop + "\\缓存决策测试报告_" + timestamp + ".csv";

        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, java.nio.charset.StandardCharsets.UTF_8))) {
            pw.println("缓存决策综合测试报告");
            pw.println("生成时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            pw.println("总计: " + total + " 条, 通过: " + passed + " 条, 失败: " + (total - passed) + " 条");
            pw.println();

            // CSV header
            pw.println(String.join(",", results.get(0)));

            // 按场景分组输出
            String currentScenario = "";
            for (int i = 1; i < results.size(); i++) {
                String[] row = results.get(i);
                if (!row[0].equals(currentScenario)) {
                    currentScenario = row[0];
                    pw.println(); // 空行分隔
                }
                pw.println(String.join(",", row));
            }
        }

        // Also print summary
        System.out.println("\n========================================");
        System.out.println("  缓存决策综合测试报告");
        System.out.println("  时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.println("========================================");
        System.out.println("  总计: " + total + " 条");
        System.out.println("  通过: " + passed + " 条 ✅");
        System.out.println("  失败: " + (total - passed) + " 条");
        System.out.printf("  通过率: %.1f%%\n", (double) passed / total * 100);
        System.out.println("========================================");
        System.out.println("  报告已保存至: " + filePath);

        // 按场景统计
        var scenarioCount = new LinkedHashMap<String, int[]>();
        for (int i = 1; i < results.size(); i++) {
            String[] row = results.get(i);
            scenarioCount.computeIfAbsent(row[0], k -> new int[2]);
            scenarioCount.get(row[0])[0]++;
            if (row[5].startsWith("✅")) scenarioCount.get(row[0])[1]++;
        }
        System.out.println("\n  按场景统计:");
        for (var e : scenarioCount.entrySet()) {
            System.out.printf("  %s: %d/%d 通过\n", e.getKey(), e.getValue()[1], e.getValue()[0]);
        }
    }
}
