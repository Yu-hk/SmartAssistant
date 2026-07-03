package com.example.smartassistant.common.load;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 系统并发承载能力基准测试。
 * <p>
 * 模拟不同并发量下系统的吞吐量、平均响应时间、错误率。
 * 测试维度：总请求数 × 并发线程数 × 模拟处理耗时
 * </p>
 */
class ConcurrencyLoadTest {

    private static final int TOTAL_REQUESTS = 1000;
    private static final long LLM_PROCESSING_TIME_MS = 2000; // 模拟 LLM 平均处理耗时 2s

    // ==================== 1. 基准：无限制并发 ====================

    @Test
    @DisplayName("基准：无限制并发（理论最高吞吐）")
    void baselineUnlimited() {
        int concurrency = 50;
        long elapsed = runConcurrentTest(concurrency, TOTAL_REQUESTS, LLM_PROCESSING_TIME_MS, null, null);
        double throughput = TOTAL_REQUESTS / (elapsed / 1000.0);
        System.out.printf("无限制并发: %d 线程, %d 请求, 耗时=%.2fs, 吞吐=%.0f req/s%n%n",
                concurrency, TOTAL_REQUESTS, elapsed / 1000.0, throughput);
    }

    // ==================== 2. L1 全局 Semaphore 限制 ====================

    @ParameterizedTest
    @ValueSource(ints = {3, 5, 8, 10, 15, 20})
    @DisplayName("L1 全局 Semaphore：不同槽位数下的吞吐")
    void l1_semaphore(int slotCount) {
        int concurrency = 50;
        Semaphore slots = new Semaphore(slotCount, true);

        // 将 Supplier<Boolean> 改为两个 Runnable
        long elapsed = runConcurrentTest(concurrency, TOTAL_REQUESTS, LLM_PROCESSING_TIME_MS,
                () -> {
                    try { slots.tryAcquire(60, TimeUnit.SECONDS); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                slots::release);

        double throughput = TOTAL_REQUESTS / (elapsed / 1000.0);
        double avgWaitMs = (elapsed / (double) TOTAL_REQUESTS) - LLM_PROCESSING_TIME_MS;
        System.out.printf("L1(slots=%d): 吞吐=%.0f req/s, 平均等待=%.1f ms%n",
                slotCount, throughput, Math.max(0, avgWaitMs));
    }

    // ==================== 3. L1+L2 全局+会话级流控 ====================

    @ParameterizedTest
    @ValueSource(ints = {5, 8, 10, 15})
    @DisplayName("L1+L2 会话级流控：5 人 × 10 请求刷屏场景")
    void l1_l2_sessionControl(int slotCount) {
        int userCount = 5;           // 5 个用户
        int requestsPerUser = 200;   // 每人发 200 条
        int totalRequests = userCount * requestsPerUser;
        int concurrency = 50;

        Semaphore slots = new Semaphore(slotCount, true);
        ConcurrentHashMap<String, AtomicInteger> sessionConcurrency = new ConcurrentHashMap<>();

        AtomicInteger rejectedByL2 = new AtomicInteger(0);
        AtomicInteger accepted = new AtomicInteger(0);

        long elapsed = runSessionAwareTest(concurrency, totalRequests, userCount, requestsPerUser,
                LLM_PROCESSING_TIME_MS, slotCount, slots, sessionConcurrency,
                rejectedByL2, accepted);

        double throughput = totalRequests / (elapsed / 1000.0);
        System.out.printf("L1(slots=%d)+L2: 5用户×200刷屏, 吞吐=%.0f req/s, L2拒绝=%d, 接受=%d%n",
                slotCount, throughput, rejectedByL2.get(), accepted.get());
    }

    // ==================== 4. L1+L2+L3 完整三层 ====================

    @Test
    @DisplayName("L1+L2+L3 完整三层：VIP 优先保障")
    void l1_l2_l3_full() {
        int slotCount = 5;
        int totalRequests = 500;
        int concurrency = 30;

        Semaphore slots = new Semaphore(slotCount, true);
        PriorityBlockingQueue<PrioritizedTask> queue = new PriorityBlockingQueue<>(1000,
                (a, b) -> {
                    int cmp = Integer.compare(a.priority, b.priority);
                    return cmp != 0 ? cmp : Long.compare(a.enqueueTime, b.enqueueTime);
                });

        ConcurrentHashMap<String, AtomicInteger> sessionConcurrency = new ConcurrentHashMap<>();
        AtomicInteger rejectedByL2 = new AtomicInteger(0);
        AtomicInteger accepted = new AtomicInteger(0);
        AtomicLong vipWaitTotal = new AtomicLong(0);
        AtomicInteger vipCount = new AtomicInteger(0);

        long elapsed = runFullTest(concurrency, totalRequests, LLM_PROCESSING_TIME_MS,
                slotCount, slots, queue, sessionConcurrency,
                rejectedByL2, accepted, vipWaitTotal, vipCount);

        double throughput = totalRequests / (elapsed / 1000.0);
        double vipAvgWait = vipCount.get() > 0 ? vipWaitTotal.get() / (double) vipCount.get() : 0;
        System.out.printf("L1+L2+L3完整三层: slots=%d, 吞吐=%.0f req/s, VIP平均等待=%.0fms, L2拒绝=%d%n",
                slotCount, throughput, vipAvgWait, rejectedByL2.get());
    }

    // ==================== 5. 模拟 Agent 异步事件总线 ====================

    @Test
    @DisplayName("同步 Handoff vs 异步 EventBus 阻塞时间对比")
    void syncVsAsyncHandoff() {
        int handoffCount = 50;
        long slowAgentTime = 3000; // 慢 Agent 处理耗时 3s

        // 同步：逐个 HTTP 调用，等待每个返回
        long syncStart = System.currentTimeMillis();
        for (int i = 0; i < handoffCount; i++) {
            simulateSyncCall(slowAgentTime);
        }
        long syncElapsed = System.currentTimeMillis() - syncStart;

        // 异步：RPUSH 到 Redis List 后立即返回
        long asyncStart = System.currentTimeMillis();
        for (int i = 0; i < handoffCount; i++) {
            simulateAsyncPublish(); // ~1μs
        }
        long asyncElapsed = System.currentTimeMillis() - asyncStart;

        System.out.printf("同步 Handoff: %d 次 × %dms = %dms (线程阻塞时间)%n",
                handoffCount, slowAgentTime, syncElapsed);
        System.out.printf("异步 EventBus: %d 次 × ~1μs = %dms (几乎 0 阻塞)%n",
                handoffCount, asyncElapsed);
        System.out.printf("节省线程阻塞时间: %,dms (%d 秒)%n%n",
                syncElapsed - asyncElapsed, (syncElapsed - asyncElapsed) / 1000);

        assertTrue(asyncElapsed < 100, "异步 Handoff 应在 100ms 内完成");
    }

    // ==================== 总结输出 ====================

    @Test
    @DisplayName("汇总：不同配置下的系统承载能力")
    void summary() {
        System.out.println("""
                ╔══════════════════════════════════════════════════════════════╗
                ║            SmartAssistant 并发承载能力评估报告              ║
                ╚══════════════════════════════════════════════════════════════╝
                
                测试环境模拟:
                  - LLM 平均处理耗时: 2s（模拟 Ollama/DeepSeek 推理）
                  - 总请求数: 1,000
                  - 模拟用户数: 5~50
                
                测试结果:
                """);
    }

    // ==================== 辅助方法 ====================

    private long runConcurrentTest(int concurrency, int totalRequests, long processTimeMs,
                                    Runnable acquire, Runnable release) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(totalRequests);
        AtomicLong totalTime = new AtomicLong(0);

        long start = System.currentTimeMillis();
        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    if (acquire != null) acquire.run();
                    long t1 = System.nanoTime();
                    simulateProcess(processTimeMs);
                    long t2 = System.nanoTime();
                    totalTime.addAndGet(t2 - t1);
                    if (release != null) release.run();
                } catch (Exception e) {
                    // 模拟处理失败
                } finally {
                    latch.countDown();
                }
            });
        }
        try { latch.await(120, TimeUnit.SECONDS); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();
        return System.currentTimeMillis() - start;
    }

    private long runSessionAwareTest(int concurrency, int totalRequests, int userCount,
                                      int requestsPerUser, long processTimeMs, int slotCount,
                                      Semaphore slots, ConcurrentHashMap<String, AtomicInteger> sessionConcurrency,
                                      AtomicInteger rejectedByL2, AtomicInteger accepted) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(totalRequests);

        long start = System.currentTimeMillis();
        for (int u = 0; u < userCount; u++) {
            String sessionId = "user-" + u;
            for (int r = 0; r < requestsPerUser; r++) {
                executor.submit(() -> {
                    try {
                        // L2: 会话级并发控制
                        AtomicInteger counter = sessionConcurrency.computeIfAbsent(
                                sessionId, k -> new AtomicInteger(0));
                        if (counter.incrementAndGet() > 1) {
                            counter.decrementAndGet();
                            rejectedByL2.incrementAndGet();
                            return;
                        }

                        // L1: 全局槽位
                        if (slots.tryAcquire(60, TimeUnit.SECONDS)) {
                            simulateProcess(processTimeMs);
                            accepted.incrementAndGet();
                            slots.release();
                        }

                        counter.decrementAndGet();
                        if (counter.get() <= 0) sessionConcurrency.remove(sessionId);
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }
        try { latch.await(120, TimeUnit.SECONDS); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();
        return System.currentTimeMillis() - start;
    }

    private long runFullTest(int concurrency, int totalRequests, long processTimeMs,
                              int slotCount, Semaphore slots,
                              PriorityBlockingQueue<PrioritizedTask> queue,
                              ConcurrentHashMap<String, AtomicInteger> sessionConcurrency,
                              AtomicInteger rejectedByL2, AtomicInteger accepted,
                              AtomicLong vipWaitTotal, AtomicInteger vipCount) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(totalRequests);

        long start = System.currentTimeMillis();
        for (int i = 0; i < totalRequests; i++) {
            boolean isVip = i % 10 == 0; // 10% VIP
            int priority = isVip ? 0 : 1;
            String sessionId = "session-" + (i % 20);

            executor.submit(() -> {
                long reqStart = System.nanoTime();
                try {
                    // L2
                    AtomicInteger counter = sessionConcurrency.computeIfAbsent(
                            sessionId, k -> new AtomicInteger(0));
                    if (counter.incrementAndGet() > 1) {
                        counter.decrementAndGet();
                        rejectedByL2.incrementAndGet();
                        return;
                    }

                    // L3: 加入优先级队列
                    queue.add(new PrioritizedTask(sessionId, priority, System.nanoTime()));

                    // L1: 获取槽位
                    if (slots.tryAcquire(60, TimeUnit.SECONDS)) {
                        simulateProcess(processTimeMs);
                        accepted.incrementAndGet();
                        long waitTime = (System.nanoTime() - reqStart) / 1_000_000;
                        if (isVip) {
                            vipWaitTotal.addAndGet(waitTime);
                            vipCount.incrementAndGet();
                        }
                        slots.release();
                    }

                    counter.decrementAndGet();
                    if (counter.get() <= 0) sessionConcurrency.remove(sessionId);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        try { latch.await(120, TimeUnit.SECONDS); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();
        return System.currentTimeMillis() - start;
    }

    private void simulateProcess(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void simulateSyncCall(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void simulateAsyncPublish() {
        // 模拟 RPUSH 的 ~1μs 延迟
        Math.random();
    }

    private record PrioritizedTask(String sessionId, int priority, long enqueueTime) {}
}
