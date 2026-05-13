package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * TF 向量相似度测试 — 验证 TF embedding 的基本性质
 */
@ExtendWith(MockitoExtension.class)
class TfEmbeddingSimilarityTest {

    @Mock private ChineseTokenizer tokenizer;
    private TfEmbeddingService tf;

    @BeforeEach
    void setUp() {
        Map<String, Set<String>> tokens = Map.ofEntries(
            Map.entry("北京今天晴", Set.of("北京", "今天", "晴")),
            Map.entry("上海今天雨", Set.of("上海", "今天", "雨")),
            Map.entry("北京明天晴", Set.of("北京", "明天", "晴")),
            Map.entry("推荐餐厅", Set.of("推荐", "餐厅")),
            Map.entry("美食推荐", Set.of("美食", "推荐")),
            Map.entry("旅游景点", Set.of("旅游", "景点")),
            Map.entry("天气", Set.of("天气")),
            Map.entry("美食", Set.of("美食"))
        );

        when(tokenizer.tokenize(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            return tokens.getOrDefault(text, Set.of(text));
        });

        tf = new TfEmbeddingService(tokenizer);
        // Build vocab
        tokens.keySet().forEach(k -> tf.embed(k));
    }

    private double sim(String a, String b) {
        float[] va = tf.embed(a);
        float[] vb = tf.embed(b);
        assertNotNull(va);
        assertNotNull(vb);
        return cosineSimilarity(va, vb);
    }

    @Test @DisplayName("同主题：共享关键词的文本相似度应高")
    void testSameTopic() {
        assertTrue(sim("北京今天晴", "北京明天晴") > 0.50, "北京天气应相似");
        assertTrue(sim("推荐餐厅", "美食推荐") > 0.30, "美食推荐应相似");
    }

    @Test @DisplayName("不同主题：无共享关键词的文本应不相似")
    void testDifferentTopic() {
        assertTrue(sim("北京今天晴", "推荐餐厅") < 0.30, "天气 vs 美食应低");
        assertTrue(sim("北京今天晴", "旅游景点") < 0.30, "天气 vs 旅游应低");
        assertTrue(sim("美食推荐", "旅游景点") < 0.30, "美食 vs 旅游应低");
    }

    @Test @DisplayName("自身相似度应接近1.0")
    void testSelf() {
        assertTrue(sim("北京今天晴", "北京今天晴") > 0.90);
    }

    @Test @DisplayName("TF向量维度 = 词典大小")
    void testVocabSize() {
        assertTrue(tf.getVocabSize() >= 10, "词典应包含所有token: " + tf.getVocabSize());
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
