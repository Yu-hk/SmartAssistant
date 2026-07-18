package com.example.smartassistant.service.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯 Java BM25 评分器单测（Okapi BM25，k1=1.5, b=0.75）。
 * <p>不依赖任何外部组件，验证打分、排序、空输入安全等核心行为。</p>
 */
@DisplayName("[Product] BM25 评分器单测")
class Bm25ScorerTest {

    private Bm25Scorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new Bm25Scorer();
        scorer.initialize(List.of(
                "iPhone 15 Pro 售价 8999 元 智能手机",
                "MacBook Air M3 笔记本 售价 7999 元",
                "iPhone 16 Pro 售价 9999 元 智能手机"
        ));
    }

    @Test
    @DisplayName("含查询词的文档得分 > 0，完全不含的文档得分 = 0")
    void scorePresence() {
        double hit = scorer.score("iPhone 智能手机", "iPhone 15 Pro 售价 8999 元 智能手机");
        double miss = scorer.score("iPhone 智能手机", "MacBook Air M3 笔记本 售价 7999 元");
        assertTrue(hit > 0, "包含查询词的文档应得正分");
        assertEquals(0.0, miss, 1e-9, "不含查询词的文档得分应为 0");
    }

    @Test
    @DisplayName("同一查询下，更相关的文档得分更高")
    void scoreRanking() {
        double s1 = scorer.score("iPhone 智能手机", "iPhone 15 Pro 售价 8999 元 智能手机");
        double s2 = scorer.score("iPhone 智能手机", "MacBook Air M3 笔记本 售价 7999 元");
        assertTrue(s1 > s2, "含 iPhone 与智能手机 的文档得分应高于不含的文档");
    }

    @Test
    @DisplayName("rank：返回按 BM25 分数降序排列的候选，且相关文档居前")
    void rankSortedDesc() {
        Map<String, String> docs = Map.of(
                "d1", "iPhone 15 Pro 售价 8999 元 智能手机",
                "d2", "MacBook Air M3 笔记本 售价 7999 元",
                "d3", "iPhone 16 Pro 售价 9999 元 智能手机"
        );
        List<Map.Entry<String, Double>> ranked = scorer.rank("iPhone 智能手机", docs);
        assertFalse(ranked.isEmpty(), "相关文档应进入排序结果");
        for (int i = 1; i < ranked.size(); i++) {
            assertTrue(ranked.get(i - 1).getValue() >= ranked.get(i).getValue(),
                    "rank 结果应按分数降序");
        }
        // 与 iPhone 相关的文档（d1 / d3）应排在最前
        String top = ranked.get(0).getKey();
        assertTrue(top.equals("d1") || top.equals("d3"), "Top-1 应为 iPhone 相关文档");
    }

    @Test
    @DisplayName("空查询 / 空文档：不抛异常，返回 0 或空列表")
    void emptyInputsSafe() {
        assertDoesNotThrow(() -> {
            assertEquals(0.0, scorer.score("", "任意文档"), 1e-9);
            assertEquals(0.0, scorer.score(null, "任意文档"), 1e-9);
            assertTrue(scorer.rank("", Map.of("d", "x")).isEmpty());
            assertTrue(scorer.rank("q", Map.of()).isEmpty());
        });
    }

    @Test
    @DisplayName("initialize(null / 空集合)：不抛异常")
    void initializeEmptySafe() {
        Bm25Scorer s2 = new Bm25Scorer();
        assertDoesNotThrow(() -> {
            s2.initialize(null);
            s2.initialize(List.of());
        });
    }
}
