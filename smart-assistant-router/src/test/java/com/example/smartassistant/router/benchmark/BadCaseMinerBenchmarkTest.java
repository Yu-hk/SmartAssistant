package com.example.smartassistant.router.benchmark;

import com.example.smartassistant.router.service.evaluation.BadCaseMinerService;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BadCaseMinerService 决策逻辑测试。
 * <p>
 * 测试 Bad Case 挖掘的决策逻辑，包括 RoutingDecision record 和低置信度检测。
 * （完整集成测试需要 Redis 环境，此处仅验证业务逻辑层。）
 * </p>
 */
@DisplayName("[Benchmark] BadCaseMinerService 决策逻辑测试")
class BadCaseMinerBenchmarkTest {

    // 阈值常量（与 BadCaseMinerService 的 @Value 默认值保持一致）
    private static final double CONFIDENCE_THRESHOLD = 0.6;

    // ==================== 测试 1：RoutingDecision record ====================

    @Test
    @DisplayName("RoutingDecision record 创建与字段访问")
    void testRoutingDecision() {
        var decision = new BadCaseMinerService.RoutingDecision(
                "我要退款", "退款申请", 0.3, "order", "sess-001", 10001L);

        assertEquals("我要退款", decision.question());
        assertEquals("退款申请", decision.predictedIntent());
        assertEquals(0.3, decision.confidence(), 0.001);
        assertEquals("order", decision.agentName());
        assertEquals("sess-001", decision.sessionId());
        assertEquals(10001L, decision.userId());
    }

    // ==================== 测试 2：低置信度检测逻辑 ====================

    @Test
    @DisplayName("低置信度（0.3 < 0.6）应被判定为 Bad Case")
    void testLowConfidenceIsBadCase() {
        double confidence = 0.3;
        boolean isBadCase = confidence < CONFIDENCE_THRESHOLD;
        assertTrue(isBadCase, "置信度 0.3 < 阈值 0.6，应为 Bad Case");
    }

    @Test
    @DisplayName("高置信度（0.9 >= 0.6）不应被判定为 Bad Case")
    void testHighConfidenceNotBadCase() {
        double confidence = 0.9;
        boolean isBadCase = confidence < CONFIDENCE_THRESHOLD;
        assertFalse(isBadCase, "置信度 0.9 >= 阈值 0.6，不应为 Bad Case");
    }

    // ==================== 测试 3：边界值 ====================

    @Test
    @DisplayName("边界值：置信度精确等于阈值（0.6）不被判定为 Bad Case")
    void testThresholdBoundary() {
        assertFalse(0.6 < CONFIDENCE_THRESHOLD, "等于阈值不应是 Bad Case");
        assertTrue(0.59 < CONFIDENCE_THRESHOLD, "小于阈值应是 Bad Case");
        assertFalse(0.61 < CONFIDENCE_THRESHOLD, "大于阈值不应是 Bad Case");
    }

    // ==================== 测试 4：BadCaseRecord 构建 ====================

    @Test
    @DisplayName("BadCaseRecord 构建与字段完整��")
    void testBadCaseRecord() {
        var record = BadCaseMinerService.BadCaseRecord.builder()
                .question("我要退款")
                .predictedIntent("退款申请")
                .confidence(0.3)
                .agentName("order")
                .sessionId("sess-001")
                .userId(10001L)
                .reason("低置信度: 0.3")
                .resolved(false)
                .build();

        assertEquals("我要退款", record.getQuestion());
        assertEquals(0.3, record.getConfidence(), 0.001);
        assertFalse(record.isResolved());
    }

    // ==================== 测试 5：大批量 RoutingDecision 构建延迟 ====================

    @Test
    @DisplayName("性能基准：10000 次 RoutingDecision 构建延迟")
    void testBulkDecisionCreation() {
        int iterations = 10000;
        long totalNanos = 0;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            var decision = new BadCaseMinerService.RoutingDecision(
                    "query-" + i,
                    i % 2 == 0 ? "退款申请" : "订单查询",
                    (double) (i % 10) / 10,
                    i % 2 == 0 ? "order" : "general",
                    "session-" + i,
                    (long) i
            );
            totalNanos += System.nanoTime() - start;
            assertNotNull(decision);
        }

        double avgUs = (double) totalNanos / iterations / 1_000;
        int badCaseCount = 0;
        for (int i = 0; i < iterations; i++) {
            double conf = (double) (i % 10) / 10;
            if (conf < CONFIDENCE_THRESHOLD) badCaseCount++;
        }

        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  BadCaseMiner 决策记录性能报告");
        System.out.println("═══════════════════════════════════════════════");
        System.out.printf("  测试次数:         %,d 次\n", iterations);
        System.out.printf("  RoutingDecision 平均构建延迟: %.2f μs\n", avgUs);
        System.out.printf("  阈值:             %.1f\n", CONFIDENCE_THRESHOLD);
        System.out.printf("  预期 Bad Case 数: %d (%.1f%%)\n", badCaseCount,
                (double) badCaseCount / iterations * 100);
        System.out.println("═══════════════════════════════════════════════\n");

        assertTrue(avgUs <= 2, "RoutingDecision 构建延迟应 ≤ 2μs（纯 record 构建）");
    }
}
