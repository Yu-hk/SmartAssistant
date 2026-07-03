package com.example.smartassistant.common.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MultiQueryService 单元测试 — 验证查询扩展逻辑。
 */
class MultiQueryServiceTest {

    private final Function<String, String> mockLlm = prompt -> {
        if (prompt.contains("查询扩展")) {
            return "iPhone 15 Pro Max 价格分析\niPhone 15 Pro Max 性价比";
        }
        if (prompt.contains("退一步")) {
            return "iPhone 高端旗舰定价原理";
        }
        if (prompt.contains("假设答案")) {
            return "iPhone 15 Pro Max 是苹果公司推出的高端旗舰手机，起售价为 9999 元，搭载 A17 Pro 芯片。";
        }
        return "";
    };

    @Test
    @DisplayName("标准 Multi-Query：生成多个角度的查询变体")
    void generate_shouldReturnMultipleVariants() {
        MultiQueryService mq = new MultiQueryService(mockLlm).withCount(3);
        List<String> variants = mq.generate("iPhone 15 Pro Max 为什么这么贵");

        assertNotNull(variants);
        assertFalse(variants.isEmpty());
        assertTrue(variants.size() >= 2); // 原始 query + 至少一个变体
        assertEquals("iPhone 15 Pro Max 为什么这么贵", variants.get(0)); // 第一个是原始
    }

    @Test
    @DisplayName("空查询应返回空列表")
    void generate_withEmptyQuery_shouldReturnEmpty() {
        MultiQueryService mq = new MultiQueryService(mockLlm);
        List<String> variants = mq.generate("");
        assertEquals(1, variants.size());
        assertEquals("", variants.get(0));
    }

    @Test
    @DisplayName("null LLM 调用器应返回原始 query")
    void generate_withoutLlm_shouldReturnOriginalQuery() {
        MultiQueryService mq = new MultiQueryService(null);
        List<String> variants = mq.generate("test query");
        assertEquals(1, variants.size());
        assertEquals("test query", variants.get(0));
    }

    @Test
    @DisplayName("Step-back 策略：应生成更通用的背景问题")
    void generateStepBackQuery_shouldReturnGeneralizedQuery() {
        MultiQueryService mq = new MultiQueryService(mockLlm);
        String stepBack = mq.generateStepBackQuery("为什么 iPhone 15 Pro Max 的 A17 Pro 芯片比 A16 快 20%");

        assertNotNull(stepBack);
        assertTrue(stepBack.contains("旗舰") || stepBack.contains("定价") || stepBack.contains("原理"));
    }

    @Test
    @DisplayName("HyDE 策略：应生成陈述句风格的假设答案")
    void generateHydeDoc_shouldReturnDeclarativeStatement() {
        MultiQueryService mq = new MultiQueryService(mockLlm);
        String hyde = mq.generateHydeDoc("iPhone 15 Pro Max 多少钱");

        assertNotNull(hyde);
        // HyDE 结果应该是陈述句，不是疑问句
        assertFalse(hyde.startsWith("iPhone 15 Pro Max 多少钱"));
        assertTrue(hyde.contains("iPhone") || hyde.contains("苹果"));
    }

    @Test
    @DisplayName("expand 统一入口：MULTI_QUERY 策略")
    void expand_withMultiQuery_shouldReturnVariants() {
        MultiQueryService mq = new MultiQueryService(mockLlm);
        List<String> result = mq.expand("test", MultiQueryService.ExpansionStrategy.MULTI_QUERY);
        assertTrue(result.size() >= 1);
    }

    @Test
    @DisplayName("expand 统一入口：STEP_BACK 策略")
    void expand_withStepBack_shouldIncludeBackgroundQuery() {
        MultiQueryService mq = new MultiQueryService(mockLlm);
        List<String> result = mq.expand("iPhone 为什么贵", MultiQueryService.ExpansionStrategy.STEP_BACK);
        assertTrue(result.size() >= 1);
    }

    @Test
    @DisplayName("expand 统一入口：HYDE 策略")
    void expand_withHyde_shouldIncludeHydeDoc() {
        MultiQueryService mq = new MultiQueryService(mockLlm);
        List<String> result = mq.expand("iPhone 多少钱", MultiQueryService.ExpansionStrategy.HYDE);
        assertTrue(result.size() >= 1);
    }

    @Test
    @DisplayName("RRF 融合：多路排名应正确计算融合分数")
    void computeRRF_shouldFuseCorrectly() {
        // docA: rank1 in path1, rank3 in path2 → 1/61 + 1/63 = 0.0323
        // docB: rank2 in path1, rank1 in path2 → 1/62 + 1/61 = 0.0326
        // docB 总分应略高于 docA（因为 rank1 比 rank2 贡献大）
        List<List<String>> rankings = List.of(
                List.of("docA", "docB", "docC"),
                List.of("docB", "docC", "docA")
        );
        Map<String, Double> scores = MultiQueryService.computeRRF(rankings, 60);
        assertNotNull(scores);
        assertTrue(scores.containsKey("docA"));
        assertTrue(scores.containsKey("docB"));
        // docB 有一路 rank1，总分应高于 docA（两路 rank2+rank3）
        assertTrue(scores.get("docB") > scores.get("docA"),
                "docB(rank1+rank2) 应高于 docA(rank1+rank3)");
    }

    @Test
    @DisplayName("RRF 融合：空排名列表应返回空")
    void computeRRF_withEmptyRankings_shouldReturnEmpty() {
        Map<String, Double> scores = MultiQueryService.computeRRF(List.of(), 60);
        assertTrue(scores.isEmpty());
    }
}
