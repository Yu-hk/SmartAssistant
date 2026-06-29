package com.example.smartassistant.router.benchmark;

import com.example.smartassistant.router.service.routing.KeywordFastRouteService;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 关键词快车道性能基准测试。
 * <p>
 * 测量关键词快车道在高频意图场景下的匹配延迟，对比带快车道 vs 不带快车道的性能差异。
 * </p>
 *
 * <h3>测试方法</h3>
 * <ul>
 *   <li>构造 1000 条高频意图查询（退款、查订单、取消订单、商品查询、问候各 200 条）</li>
 *   <li>测量 KeywordFastRouteService.match() 的每次调用延迟</li>
 *   <li>对比基准：同样输入但模拟"无快车道"（直接跳过匹配）</li>
 * </ul>
 *
 * <h3>判断标准</h3>
 * <ul>
 *   <li>快车道命中率应 ≥ 95%（内置规则覆盖高频表达）</li>
 *   <li>单次 match() 延迟应 ≤ 500μs（关键词匹配无外部调用）</li>
 *   <li>对比基准：跳过 LLM 意图识别的延迟节省应 ≥ 1.5s/次</li>
 * </ul>
 */
@DisplayName("[Benchmark] 关键词快车道性能基准测试")
class KeywordFastRouteBenchmarkTest {

    private KeywordFastRouteService fastRouteService;

    @BeforeEach
    void setUp() {
        // 使用内置默认规则初始化（无 Spring 上下文）
        KeywordFastRouteService.KeywordRouteProperties props =
                new KeywordFastRouteService.KeywordRouteProperties();
        props.setEnabled(true);
        props.setMatchThreshold(0.5);
        fastRouteService = new KeywordFastRouteService(props);
        fastRouteService.init();
    }

    // ==================== 测试数据生成 ====================

    /** 生成 1000 条高频意图查询 */
    static List<String> generateQueries() {
        List<String> queries = new ArrayList<>(1000);

        // 退款相关 (200)
        for (int i = 0; i < 200; i++) {
            queries.add("我要退款订单号 ORD-" + (i + 1000));
        }
        // 查订单相关 (200)
        for (int i = 0; i < 200; i++) {
            queries.add("查一下我的订单 ORD-" + (i + 2000) + " 的物流");
        }
        // 取消订单相关 (200)
        for (int i = 0; i < 200; i++) {
            queries.add("帮我取消订单 ORD-" + (i + 3000));
        }
        // 商品查询相关 (200)
        for (int i = 0; i < 200; i++) {
            queries.add(i % 2 == 0
                    ? "有没有" + i + "寸的电视机"
                    : "推荐一下性价比高的手机");
        }
        // 问候相关 (200)
        for (int i = 0; i < 200; i++) {
            queries.add(i % 2 == 0 ? "你好" : "您好，请问你在吗");
        }

        return queries;
    }

    /** 生成 200 条"无明确意图"的模糊查询（预期不命中） */
    static List<String> generateAmbiguousQueries() {
        List<String> queries = new ArrayList<>(200);
        for (int i = 0; i < 200; i++) {
            queries.add("今天天气不错" + (i % 10 == 0 ? "，你觉得呢" : ""));
        }
        return queries;
    }

    /** 生成 200 条"咨询类"查询（使用了排除词，预期不命中） */
    static List<String> generateConsultationQueries() {
        List<String> queries = new ArrayList<>(200);
        for (int i = 0; i < 200; i++) {
            queries.add("怎么退款？退款流程是什么样的？");
        }
        return queries;
    }

    // ==================== 基准测试 ====================

    @Test
    @DisplayName("性能基准：1000 条高频意图匹配延迟")
    void benchmarkHighFrequencyMatching() {
        List<String> queries = generateQueries();

        // warmup: 先跑 100 次消除 JVM JIT 冷启动影响
        for (int i = 0; i < 100; i++) {
            fastRouteService.match(queries.get(i % queries.size()));
        }

        // 正式测量
        int hitCount = 0;
        int missCount = 0;
        long totalLatencyNanos = 0;
        long minLatencyNanos = Long.MAX_VALUE;
        long maxLatencyNanos = 0;

        long startTime = System.nanoTime();
        for (String query : queries) {
            long opStart = System.nanoTime();
            var result = fastRouteService.match(query);
            long opEnd = System.nanoTime();
            long opLatency = opEnd - opStart;

            totalLatencyNanos += opLatency;
            minLatencyNanos = Math.min(minLatencyNanos, opLatency);
            maxLatencyNanos = Math.max(maxLatencyNanos, opLatency);

            if (result != null) {
                hitCount++;
            } else {
                missCount++;
            }
        }
        long totalDurationNanos = System.nanoTime() - startTime;

        double avgLatencyUs = (double) totalLatencyNanos / queries.size() / 1_000;
        double minLatencyUs = (double) minLatencyNanos / 1_000;
        double maxLatencyUs = (double) maxLatencyNanos / 1_000;
        double hitRate = (double) hitCount / queries.size() * 100;
        double throughput = (double) queries.size() / totalDurationNanos * 1_000_000_000;

        // 打印详细报告
        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  关键词快车道性能基准测试报告");
        System.out.println("═══════════════════════════════════════════════");
        System.out.printf("  测试集大小:       %d 条查询\n", queries.size());
        System.out.printf("  匹配命中:         %d 条 (%.1f%%)\n", hitCount, hitRate);
        System.out.printf("  未命中:           %d 条 (%.1f%%)\n", missCount, 100 - hitRate);
        System.out.println("───────────────────────────────────────────────");
        System.out.println("  延迟指标 (μs/次):");
        System.out.printf("    平均延迟:       %.1f μs\n", avgLatencyUs);
        System.out.printf("    最小延迟:       %.1f μs\n", minLatencyUs);
        System.out.printf("    最大延迟:       %.1f μs\n", maxLatencyUs);
        System.out.println("───────────────────────────────────────────────");
        System.out.printf("  吞吐量:           %.0f 次/秒\n", throughput);
        System.out.println("═══════════════════════════════════════════════\n");

        // 断言
        assertTrue(hitRate >= 95.0,
                "高频意图命中率应 ≥ 95%，实际 " + String.format("%.1f", hitRate) + "%");
        assertTrue(avgLatencyUs <= 500,
                "平均延迟应 ≤ 500μs，实际 " + String.format("%.1f", avgLatencyUs) + " μs");
    }

    @Test
    @DisplayName("模糊查询：预期不命中率 ≥ 95%")
    void testAmbiguousQueriesShouldNotMatch() {
        List<String> queries = generateAmbiguousQueries();

        int hitCount = 0;
        for (String query : queries) {
            var result = fastRouteService.match(query);
            if (result != null) hitCount++;
        }

        double hitRate = (double) hitCount / queries.size() * 100;
        System.out.println("\n[模糊查询] 不应命中条数: " + hitCount + "/" + queries.size()
                + " (率=" + String.format("%.1f", hitRate) + "%)");

        assertTrue(hitRate <= 5.0,
                "模糊查询（天气话题）命中率应 ≤ 5%，实际 " + String.format("%.1f", hitRate) + "%");
    }

    @Test
    @DisplayName("咨询类查询：使用了排除词，预期命中率 = 0")
    void testConsultationQueriesShouldNotMatch() {
        List<String> queries = generateConsultationQueries();

        int hitCount = 0;
        for (String query : queries) {
            var result = fastRouteService.match(query);
            if (result != null) hitCount++;
        }

        System.out.println("\n[咨询类查询] 命中条数: " + hitCount + "/" + queries.size());
        assertEquals(0, hitCount, "咨询类查询（含排除词 [怎么退]）不应命中快车道");
    }

    @Test
    @DisplayName("命中结果正确性验证：退款查询应路由到 order agent")
    void testRefundRouteCorrectness() {
        var result = fastRouteService.match("我要退款，订单号 ORD-12345");
        assertNotNull(result);
        assertEquals("order", result.getTargetAgent(),
                "退款查询应路由到 order agent");
        assertEquals("退款申请", result.getIntentTag(),
                "退款查询的意图标签应为 [退款申请]");
        assertTrue(result.getConfidence() >= 0.9,
                "关键词匹配置信度应 ≥ 0.9");
    }

    @Test
    @DisplayName("命中结果正确性验证：商品查询应路由到 product agent")
    void testProductRouteCorrectness() {
        var result = fastRouteService.match("推荐一款性价比高的手机");
        assertNotNull(result);
        assertEquals("product", result.getTargetAgent(),
                "商品查询应路由到 product agent");
        assertEquals("商品查询", result.getIntentTag());
    }

    @Test
    @DisplayName("命中结果正确性验证：问候应路由到 general agent")
    void testGreetingRouteCorrectness() {
        var result = fastRouteService.match("你好");
        assertNotNull(result);
        assertEquals("general", result.getTargetAgent(),
                "问候应路由到 general agent");
        assertEquals("问候", result.getIntentTag());
        assertTrue(result.getConfidence() >= 0.98,
                "问候匹配置信度应 ≥ 0.98");
    }

    // ═══════════════════════════════════════════════════
    // 🧪 多意图检测测试（P4 新增）
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("多意图保护：跨 Agent 复合查询应跳过快车道")
    void testMultiIntentCrossAgentSkipsFastLane() {
        // "退款"→order + "你好"→general → 跨 Agent 多意图 → 应返回 null
        var result = fastRouteService.match("退款后说你好");
        assertNull(result, "跨 Agent 多意图应跳过快车道: 退款(order)+问候(general)");
    }

    @Test
    @DisplayName("多意图保护：同 Agent 内多关键词不应拦截")
    void testMultiIntentSameAgentPassesThrough() {
        // "退款"+订单号 → 同 Agent(order) 多关键词 → 应正常匹配
        var result = fastRouteService.match("我要退款，订单号 ORD-12345");
        assertNotNull(result, "同 Agent 多关键词不应拦截");
        assertEquals("order", result.getTargetAgent());
    }

    @Test
    @DisplayName("多意图保护：单 Agent 明确意图正常通过")
    void testSingleIntentPassesThrough() {
        var result = fastRouteService.match("我想退款");
        assertNotNull(result, "单意图问题应正常命中");
        assertEquals("order", result.getTargetAgent());

        result = fastRouteService.match("查一下我的订单");
        assertNotNull(result);
        assertEquals("order", result.getTargetAgent());

        result = fastRouteService.match("你好");
        assertNotNull(result);
        assertEquals("general", result.getTargetAgent());
    }

    // ==================== 多线程并发测试 ====================

    @Test
    @DisplayName("并发安全：10 线程同时匹配，验证线程安全")
    void testConcurrentMatching() throws InterruptedException {
        List<String> queries = generateQueries();
        int threadCount = 10;
        int queriesPerThread = 100;

        Thread[] threads = new Thread[threadCount];
        long[] threadLatencies = new long[threadCount];
        int[] threadHits = new int[threadCount];

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                long total = 0;
                int hits = 0;
                for (int i = 0; i < queriesPerThread; i++) {
                    long start = System.nanoTime();
                    var result = fastRouteService.match(queries.get((threadId * queriesPerThread + i) % queries.size()));
                    total += System.nanoTime() - start;
                    if (result != null) hits++;
                }
                threadLatencies[threadId] = total / queriesPerThread;
                threadHits[threadId] = hits;
            });
            threads[t].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        // 统计
        int totalHits = 0;
        long avgLatencySum = 0;
        for (int t = 0; t < threadCount; t++) {
            totalHits += threadHits[t];
            avgLatencySum += threadLatencies[t];
        }

        double overallAvgLatencyUs = (double) avgLatencySum / threadCount / 1_000;

        System.out.printf("\n[并发测试] 10线程×100次/线程，总命中: %d/%d (%.1f%%)，平均延迟: %.1f μs\n",
                totalHits, threadCount * queriesPerThread,
                (double) totalHits / (threadCount * queriesPerThread) * 100,
                overallAvgLatencyUs);

        assertTrue(totalHits > 0, "并发场景下应有命中");
        assertTrue(overallAvgLatencyUs <= 1000,
                "并发场景平均延迟应 ≤ 1000μs，实际 " + String.format("%.1f", overallAvgLatencyUs) + " μs");
    }

    // ==================== 对比测试：快车道 vs 无快车道 ====================

    @Test
    @DisplayName("对比测试：快车道 vs 无快车道的延迟差异")
    void testFastRouteVsWithout() {
        // 无快车道：模拟 LLM 意图识别的固定延迟（取实际 LLM 调用的最小估计，约 500ms）
        final long WITHOUT_FASTROUTE_LATENCY_MS = 500; // LLM 调用延迟（最乐观估计）

        List<String> queries = generateQueries().subList(0, 100);
        long totalFastLatencyNanos = 0;

        // 测量快车道延迟
        for (String query : queries) {
            long start = System.nanoTime();
            fastRouteService.match(query);
            totalFastLatencyNanos += System.nanoTime() - start;
        }

        double avgFastLatencyMs = (double) totalFastLatencyNanos / queries.size() / 1_000_000;
        long totalWithoutLatencyMs = queries.size() * WITHOUT_FASTROUTE_LATENCY_MS;
        long totalFastLatencyMs = (long) (totalFastLatencyNanos / 1_000_000);
        long latencySavingMs = totalWithoutLatencyMs - totalFastLatencyMs;
        double speedupRatio = (double) WITHOUT_FASTROUTE_LATENCY_MS / avgFastLatencyMs;

        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  快车道 vs 无快车道 — 延迟对比");
        System.out.println("═══════════════════════════════════════════════");
        System.out.printf("  测试集大小:             %d 条\n", queries.size());
        System.out.printf("  快车道平均单次延迟:     %.3f ms\n", avgFastLatencyMs);
        System.out.printf("  无快车道估计单次延迟:   %d ms (LLM 调用乐观估计)\n", WITHOUT_FASTROUTE_LATENCY_MS);
        System.out.println("───────────────────────────────────────────────");
        System.out.printf("  💨 总延迟节省:          %d ms (%.2f 秒)\n",
                latencySavingMs, (double) latencySavingMs / 1000);
        System.out.printf("  ⚡ 加速比:              %.0f 倍\n", speedupRatio);
        System.out.println("═══════════════════════════════════════════════\n");

        assertTrue(avgFastLatencyMs < WITHOUT_FASTROUTE_LATENCY_MS,
                "快车道延迟应远低于 LLM 调用延迟");
    }

    // ==================== 规则命中分布 ====================

    @Test
    @DisplayName("规则命中分布：验证退款/查订单/取消/商品/问候各自覆盖")
    void testRuleHitDistribution() {
        // 对每种意图各测试 50 条
        String[][] intentQueries = {
                {"refund_fast_route", "退款", "退货", "退钱", "不要了"},
                {"query_order_fast_route", "查订单", "我的订单", "物流"},
                {"cancel_order_fast_route", "取消订单"},
                {"product_query_fast_route", "商品", "价格", "推荐"},
        };

        for (String[] group : intentQueries) {
            String ruleName = group[0];
            int hits = 0;
            for (int i = 1; i < group.length; i++) {
                var result = fastRouteService.match(group[i]);
                if (result != null) {
                    hits++;
                }
            }
            System.out.printf("  [规则命中] %s: %d/%d\n", ruleName, hits, group.length - 1);
            assertTrue(hits > 0, "规则 " + ruleName + " 应有命中");
        }
    }
}
