package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * TF 向量相似度测试 — 50+ 字长文本模拟 Agent 真实回复
 * <p>
 * Token 模拟中国分词器 IKAnalyzer 的输出粒度。
 */
@ExtendWith(MockitoExtension.class)
class TfEmbeddingSimilarityTest {

    @Mock private ChineseTokenizer tokenizer;
    private TfEmbeddingService tf;
    private static final double THRESHOLD = 0.15;

    // 50+ 字长回复的分词结果（模拟 IKAnalyzer）
    // "北京今天天气晴朗气温最高22度最低15度东南风3到4级体感舒适
    //  空气良好适合户外活动建议携带外套注意防晒紫外线中等早晚温差较大"
    private static final Set<String> WEATHER_TOKENS = Set.of(
            "北京", "今天", "天气", "晴朗", "气温", "最高", "最低",
            "东南风", "体感", "舒适", "空气", "良好",
            "适合", "户外", "活动", "建议", "携带", "外套",
            "注意", "防晒", "紫外线", "中等", "早晚", "温差", "较大");

    // "为您推荐北京烤鸭全聚德老字号餐厅位于前门大街交通便利
    //  人均约150元环境优雅服务周到招牌菜品口味正宗建议提前预约"
    private static final Set<String> FOOD_TOKENS = Set.of(
            "推荐", "北京", "烤鸭", "全聚德", "老字号",
            "餐厅", "位于", "前门", "大街", "交通", "便利",
            "人均", "环境", "优雅", "服务", "周到",
            "招牌", "菜品", "口味", "正宗", "建议", "提前", "预约");

    // "推荐游览故宫天坛颐和园长城门票约60元开放时间上午8点到下午5点
    //  游玩建议故宫半天颐和园一天交通可乘地铁公交"
    private static final Set<String> TRAVEL_TOKENS = Set.of(
            "推荐", "游览", "故宫", "天坛", "颐和园", "长城",
            "门票", "开放", "时间", "上午", "下午",
            "游玩", "建议", "一天", "半天", "交通", "地铁", "公交");

    // 扩展文本
    private static final Set<String> EXT_COLD = Set.of("穿", "衣服", "保暖", "温差", "温度", "冷");
    private static final Set<String> EXT_RUN = Set.of("适合", "跑步", "户外", "活动");
    private static final Set<String> EXT_RAIN = Set.of("下雨", "带伞", "天气");
    private static final Set<String> EXT_CLOTHES = Set.of("穿", "衣服", "建议", "温度");
    private static final Set<String> EXT_COLD_NIGHT = Set.of("晚上", "降温", "温差", "温度");
    private static final Set<String> EXT_FOOD = Set.of("好吃", "美食", "推荐", "餐厅");
    private static final Set<String> EXT_TRAVEL = Set.of("好玩", "景点", "推荐");
    private static final Set<String> EXT_PLAN = Set.of("规划", "行程", "路线");
    private static final Set<String> EXT_POI = Set.of("故宫", "开门");

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, nA = 0, nB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            nA += (double) a[i] * a[i];
            nB += (double) b[i] * b[i];
        }
        return Math.sqrt(nA) * Math.sqrt(nB) == 0 ? 0 : dot / (Math.sqrt(nA) * Math.sqrt(nB));
    }

    @BeforeEach
    void setUp() {
        lenient().when(tokenizer.tokenize(anyString())).thenAnswer(inv -> {
            String t = inv.getArgument(0);
            // Map test texts to their expected IKAnalyzer token sets
            switch (t) {
                case "WEATHER": return WEATHER_TOKENS;
                case "FOOD":    return FOOD_TOKENS;
                case "TRAVEL":  return TRAVEL_TOKENS;
                case "冷不冷": case "冷": return EXT_COLD;
                case "适合跑步吗": return EXT_RUN;
                case "需要带伞吗": return EXT_RAIN;
                case "适合穿什么": return EXT_CLOTHES;
                case "晚上降温吗": return EXT_COLD_NIGHT;
                case "有什么好吃的": return EXT_FOOD;
                case "有什么好玩的": return EXT_TRAVEL;
                case "帮我规划行程": return EXT_PLAN;
                case "故宫几点开门": return EXT_POI;
                default: return Set.of(t);
            }
        });

        tf = new TfEmbeddingService(tokenizer);
        // Build vocab by embedding all test texts
        for (String k : new String[]{"WEATHER","FOOD","TRAVEL",
            "冷不冷","适合跑步吗","需要带伞吗","适合穿什么","晚上降温吗",
            "有什么好吃的","有什么好玩的","帮我规划行程","故宫几点开门"}) {
            tf.embed(k);
        }
    }

    private double sim(String a, String b) {
        float[] va = tf.embed(a);
        float[] vb = tf.embed(b);
        assertNotNull(va);
        assertNotNull(vb);
        return cosineSimilarity(va, vb);
    }

    @Test
    @DisplayName("天气长回复 vs 各扩展 — 完整诊断")
    void testAllDiagnostics() {
        System.out.println("=== 50+字天气回复 vs 各扩展 ===");
        System.out.println("天气回复(" + WEATHER_TOKENS.size() + " tokens): " + WEATHER_TOKENS);

        Object[][] cases = {
            {"冷不冷",       EXT_COLD,      true,  "温度/温差共享"},
            {"适合跑步吗",   EXT_RUN,       true,  "适合/户外/活动共享"},
            {"需要带伞吗",   EXT_RAIN,      true,  "天气共享"},
            {"适合穿什么",   EXT_CLOTHES,   true,  "穿/衣服/建议共享"},
            {"晚上降温吗",   EXT_COLD_NIGHT,true,  "温差/温度共享"},
            {"有什么好吃的", EXT_FOOD,      false, "美食领域"},
            {"有什么好玩的", EXT_TRAVEL,    false, "旅行领域"},
            {"帮我规划行程", EXT_PLAN,      false, "行程规划"},
            {"故宫几点开门", EXT_POI,       false, "景点查询"},
        };

        int pass = 0, fail = 0;
        for (var c : cases) {
            String name = (String) c[0];
            boolean expectHit = (boolean) c[2];
            String desc = (String) c[3];

            double s = sim(name, "WEATHER");
            boolean hit = s >= THRESHOLD;
            boolean ok = hit == expectHit;

            String status = ok ? "✓" : "✗";
            String result = hit ? "命中" : "未命中";
            System.out.printf("  [%s] %s: %.4f %s (应%s) %s%n",
                    status, name, s, result, expectHit ? "命中" : "未命中", desc);

            if (ok) pass++; else fail++;
        }
        System.out.printf("\n结果: %d/%d 通过, %d 失败\n", pass, pass+fail, fail);
    }

    @Test
    @DisplayName("跨领域长回复区分度")
    void testCrossDomain() {
        double wf = sim("WEATHER", "FOOD");
        double wt = sim("WEATHER", "TRAVEL");
        double ft = sim("FOOD", "TRAVEL");

        System.out.println("\n=== 跨领域长回复 ===");
        System.out.printf("天气 vs 美食  50+字: %.4f\n", wf);
        System.out.printf("天气 vs 旅行  50+字: %.4f\n", wt);
        System.out.printf("美食 vs 旅行  50+字: %.4f\n", ft);

        assertTrue(wf < THRESHOLD, "天气 vs 美食应 <0.15: " + String.format("%.4f", wf));
        assertTrue(wt < THRESHOLD, "天气 vs 旅行应 <0.15: " + String.format("%.4f", wt));
    }

    @Test
    @DisplayName("长天气回复自身相似度")
    void testSelfWeather() {
        double s = sim("WEATHER", "WEATHER");
        System.out.printf("\n天气 50+字 vs 自身: %.4f (应≈1.0)\n", s);
        assertTrue(s > 0.90);
    }

    @Test
    @DisplayName("词典大小")
    void testVocab() {
        System.out.printf("\nTF 词典大小: %d\n", tf.getVocabSize());
        assertTrue(tf.getVocabSize() >= 40);
    }
}
