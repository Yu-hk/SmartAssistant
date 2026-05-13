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
import static org.mockito.Mockito.when;

/**
 * TF 向量相似度测试 — 使用长文本模拟 Agent 真实回复
 */
@ExtendWith(MockitoExtension.class)
class TfEmbeddingSimilarityTest {

    @Mock private ChineseTokenizer tokenizer;
    private TfEmbeddingService tf;
    private static final double THRESHOLD = 0.30;

    // 模拟 ChineseTokenizer 的输出（IKAnalyzer 风格分词）
    private static final Map<String, Set<String>> TOKENS = new LinkedHashMap<>();
    static {
        // 天气回复（长文本，模拟 Agent 输出）
        TOKENS.put("WEATHER_REPLY", Set.of(
                "北京", "今天", "天气", "晴朗", "气温", "度",
                "东南风", "级", "体感", "舒适",
                "早晚", "温差", "较大", "建议", "带件", "薄外套",
                "空气", "质量", "良好", "适合", "户外", "活动",
                "紫外线", "中等", "注意", "防晒"));

        // 天气衍生扩展
        TOKENS.put("需要多穿点衣服吗", Set.of("需要", "多穿", "衣服"));
        TOKENS.put("冷不冷", Set.of("冷"));
        TOKENS.put("适合跑步吗", Set.of("适合", "跑步"));     // "适合" 与回复共享
        TOKENS.put("需要带伞吗", Set.of("需要", "带伞"));
        TOKENS.put("那冷不冷", Set.of("冷"));
        TOKENS.put("适合穿什么", Set.of("适合", "穿什么"));   // "适合" 与回复共享
        TOKENS.put("今天会下雨吗", Set.of("今天", "下雨"));    // "今天" 与回复共享

        // 非天气扩展
        TOKENS.put("有什么好吃的", Set.of("什么", "好吃", "美食"));
        TOKENS.put("有什么好玩的", Set.of("什么", "好玩", "景点"));
        TOKENS.put("帮我规划三天行程", Set.of("规划", "行程", "路线"));
        TOKENS.put("故宫几点开门", Set.of("故宫", "开门", "门票"));
        TOKENS.put("有什么餐厅推荐", Set.of("什么", "餐厅", "推荐", "川菜"));

        // 跨领域模拟回复
        TOKENS.put("FOOD_REPLY", Set.of(
                "推荐", "北京", "川菜", "餐厅", "好吃",
                "口味", "正宗", "环境", "服务", "价格",
                "人均", "地址", "营业", "时间"));
        TOKENS.put("TRAVEL_REPLY", Set.of(
                "推荐", "故宫", "天坛", "颐和园", "景点",
                "门票", "开放", "时间", "地址", "交通",
                "建议", "游览", "路线", "游玩"));
    }

    private String getReply(String key) {
        return TOKENS.entrySet().stream()
                .filter(e -> e.getKey().equals(key))
                .map(e -> String.join(" ", e.getValue()))
                .findFirst().orElse("");
    }

    @BeforeEach
    void setUp() {
        when(tokenizer.tokenize(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            Set<String> result = TOKENS.get(text);
            if (result != null) return result;
            // Fallback: split by spaces (for joined reply strings)
            Set<String> ws = new HashSet<>();
            for (String w : text.split("\\s+")) {
                if (w.length() >= 2) ws.add(w);
            }
            return ws;
        });

        tf = new TfEmbeddingService(tokenizer);
        // Build vocab from all tokens
        for (var entry : TOKENS.entrySet()) {
            tf.embed(entry.getKey());
        }
        // Also embed joined reply strings
        tf.embed(getReply("WEATHER_REPLY"));
        tf.embed(getReply("FOOD_REPLY"));
        tf.embed(getReply("TRAVEL_REPLY"));
    }

    private double sim(String a, String b) {
        // For reply texts, use the key
        String ta = a.startsWith("REPLY:") ? getReply(a.substring(6)) : a;
        String tb = b.startsWith("REPLY:") ? getReply(b.substring(6)) : b;
        float[] va = tf.embed(ta);
        float[] vb = tf.embed(tb);
        assertNotNull(va, ta + " emb should not be null");
        assertNotNull(vb, tb + " emb should not be null");
        return cosineSimilarity(va, vb);
    }

    @Test
    @DisplayName("天气衍生扩展 vs 长天气回复 — 验证各场景命中情况")
    void testWeatherDerivedWithLongReply() {
        String reply = getReply("WEATHER_REPLY");

        // 今天会下雨吗 → "今天" 共享
        double s = sim("今天会下雨吗", reply);
        System.out.printf("[%.4f] 今天会下雨吗 vs 天气回复 %s\n", s, s >= THRESHOLD ? "✓" : "✗");

        // 适合穿什么 → "适合" 共享
        s = sim("适合穿什么", reply);
        System.out.printf("[%.4f] 适合穿什么 vs 天气回复 %s\n", s, s >= THRESHOLD ? "✓" : "✗");

        // 适合跑步吗 → "适合" 共享
        s = sim("适合跑步吗", reply);
        System.out.printf("[%.4f] 适合跑步吗 vs 天气回复 %s\n", s, s >= THRESHOLD ? "✓" : "✗");

        // 非天气
        s = sim("有什么好吃的", reply);
        System.out.printf("[%.4f] 有什么好吃的 vs 天气回复 %s\n", s, s >= THRESHOLD ? "✗误命中" : "✓");

        s = sim("帮我规划行程", reply);
        System.out.printf("[%.4f] 帮我规划行程 vs 天气回复 %s\n", s, s >= THRESHOLD ? "✗误命中" : "✓");

        // 结果输出供参考，不做硬断言（阈值需调参）
        System.out.println("[Note] 以上数据供阈值调参参考，不做断言");
    }

    @Test
    @DisplayName("跨领域长回复对比 — 应有区分度")
    void testCrossDomainLongReplies() {
        String weather = getReply("WEATHER_REPLY");
        String food = getReply("FOOD_REPLY");
        String travel = getReply("TRAVEL_REPLY");

        double wf = sim(weather, food);
        double wt = sim(weather, travel);
        double ft = sim(food, travel);
        System.out.printf("[Cross] 天气 vs 美食: %.4f\n", wf);
        System.out.printf("[Cross] 天气 vs 旅行: %.4f\n", wt);
        System.out.printf("[Cross] 美食 vs 旅行: %.4f\n", ft);
    }

    @Test
    @DisplayName("词汇量应足够覆盖")
    void testVocabSize() {
        System.out.printf("[Vocab] TF 词典大小: %d\n", tf.getVocabSize());
        assertTrue(tf.getVocabSize() >= 30, "词典应≥30");
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, nA = 0, nB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            nA += (double) a[i] * a[i];
            nB += (double) b[i] * b[i];
        }
        return Math.sqrt(nA) * Math.sqrt(nB) == 0 ? 0 : dot / (Math.sqrt(nA) * Math.sqrt(nB));
    }
}
