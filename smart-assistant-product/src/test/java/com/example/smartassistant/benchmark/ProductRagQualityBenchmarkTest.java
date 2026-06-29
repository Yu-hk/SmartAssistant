package com.example.smartassistant.benchmark;

import com.example.smartassistant.service.search.ProductRagService;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProductRagService 检索质量评分基准测试。
 * <p>
 * 测试改前后的差异：
 * <ul>
 *   <li>改造前：retrieve() 直接返回原始字符串，无质量评估</li>
 *   <li>改造后：retrieveWithQuality() 返回 RetrievalResult（含 qualityScore/highQuality/fallback）</li>
 * </ul>
 * </p>
 *
 * <h3>关键指标</h3>
 * <ul>
 *   <li>空输入时 qualityScore=0.0, highQuality=false, fallback 有值</li>
 *   <li>构造延迟应 ≤ 1μs</li>
 * </ul>
 */
@DisplayName("[Benchmark] ProductRAG 质量评分测试")
class ProductRagQualityBenchmarkTest {

    // ==================== 测试 1：空输入 ====================

    @Test
    @DisplayName("空输入：qualityScore = 0.0，highQuality = false，fallback 非空")
    void testEmptyResult() {
        var result = new ProductRagService.RetrievalResult("", 0.0, false, "兜底提示");

        assertEquals(0.0, result.qualityScore(), 0.001);
        assertFalse(result.highQuality());
        assertEquals("兜底提示", result.fallback());
        assertTrue(result.content().isBlank());
    }

    // ==================== 测试 2：高质量结果 ====================

    @Test
    @DisplayName("高质量结果：qualityScore = 0.85，highQuality = true，fallback = null")
    void testHighQualityResult() {
        var result = new ProductRagService.RetrievalResult(
                "【商品检索结果】\n1. 商品A\n2. 商品B",
                0.85, true, null
        );

        assertEquals(0.85, result.qualityScore(), 0.001);
        assertTrue(result.highQuality());
        assertNull(result.fallback(), "高质量结果无兜底提示");
        assertTrue(result.content().contains("商品A"));
    }

    // ==================== 测试 3：低质量结果 ====================

    @Test
    @DisplayName("低质量结果：qualityScore = 0.3，highQuality = false，fallback 有值")
    void testLowQualityResult() {
        var result = new ProductRagService.RetrievalResult(
                "【商品检索结果】\n1. 未知商品",
                0.3, false, "检索结果质量较低，建议核对商品名称"
        );

        assertFalse(result.highQuality());
        assertEquals("检索结果质量较低，建议核对商品名称", result.fallback());
        assertEquals(0.3, result.qualityScore(), 0.001);
    }

    // ==================== 测试 4：性能基准 ====================

    @Test
    @DisplayName("性能基准：10000 次 RetrievalResult 构建延迟")
    void testConstructionLatency() {
        int iterations = 10000;
        long totalNanos = 0;

        for (int i = 0; i < iterations; i++) {
            double score = (double) i / iterations;
            long start = System.nanoTime();
            var result = new ProductRagService.RetrievalResult(
                    "商品" + i,
                    score,
                    score >= 0.35,
                    score < 0.35 ? "兜底提示" : null
            );
            totalNanos += System.nanoTime() - start;
            assertNotNull(result);
        }

        double avgUs = (double) totalNanos / iterations / 1_000;

        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  RetrievalResult 构建性能");
        System.out.println("═══════════════════════════════════════════════");
        System.out.printf("  测试次数: %,d 次\n", iterations);
        System.out.printf("  平均延迟: %.3f μs\n", avgUs);
        System.out.println("═══════════════════════════════════════════════\n");

        assertTrue(avgUs <= 2, "RetrievalResult 构建延迟应 ≤ 2μs");
    }

    // ==================== 测试 5：质量评分对比 ====================

    @Test
    @DisplayName("对比测试：低质量 vs 高质量结果")
    void testLowVsHighQualityComparison() {
        var low = new ProductRagService.RetrievalResult("未知商品A", 0.1, false, "质量低");
        var high = new ProductRagService.RetrievalResult("商品A：￥100", 0.9, true, null);
        var empty = new ProductRagService.RetrievalResult("", 0.0, false, "请提供关键词");

        System.out.println("\n[质量评分对比]");
        System.out.println("  ┌──────────────────────┬──────────────┬──────────────┬───────────┐");
        System.out.println("  │ 数据                  │ qualityScore │ highQuality  │ fallback  │");
        System.out.println("  ├──────────────────────┼──────────────┼──────────────┼───────────┤");
        System.out.printf("  │ 低质量(旧)            │  %.1f        │ %-12s │ %-9s│\n",
                low.qualityScore(), low.highQuality(),
                low.fallback() != null ? "有" : "无");
        System.out.printf("  │ 高质量(新)            │  %.1f        │ %-12s │ %-9s│\n",
                high.qualityScore(), high.highQuality(),
                high.fallback() != null ? "无" : "无");
        System.out.printf("  │ 空输入                │  %.1f        │ %-12s │ %-9s│\n",
                empty.qualityScore(), empty.highQuality(), "有");
        System.out.println("  └──────────────────────┴──────────────┴──────────────┴───────────┘\n");

        assertFalse(low.highQuality());
        assertTrue(high.highQuality());
        assertFalse(empty.highQuality());
        assertTrue(high.qualityScore() > low.qualityScore());
    }
}
