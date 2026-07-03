package com.example.smartassistant.common.rag.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对比测试：旧方案(Hash全量读取) vs 新方案(Sorted Set范围查询, 会话级流控)。
 * <p>
 * 不依赖Redis，用内存数据结构模拟核心操作，对比算法复杂度和实际耗时。
 * </p>
 */
class AlgorithmComparisonTest {

    private static final int EVENT_COUNT = 10000;

    // ==================== 1. SSE 续传：Hash vs Sorted Set ====================

    @Test
    @DisplayName("Hash HGETALL + 遍历过滤 vs Sorted Set ZRANGEBYSCORE")
    void sseResumeComparison() {
        // 模拟 10000 条缓存事件，lastEventId = 9000（需补发 1000 条）
        int lastEventId = 9000;

        // --- 旧方案：Hash (HGETALL + 遍历过滤) ---
        Map<String, String> hashStore = new HashMap<>();
        IntStream.rangeClosed(1, EVENT_COUNT).forEach(i ->
                hashStore.put(String.valueOf(i), "data_" + i));

        long hashStart = System.nanoTime();
        // Step 1: HGETALL — 读取全部
        List<Long> pendingSeqs = new ArrayList<>();
        for (String key : hashStore.keySet()) {
            long seq = Long.parseLong(key);
            if (seq > lastEventId) pendingSeqs.add(seq);
        }
        // Step 2: 排序
        Collections.sort(pendingSeqs);
        // Step 3: 逐个读取
        int hashBytes = 0;
        for (long seq : pendingSeqs) {
            String data = hashStore.get(String.valueOf(seq));
            if (data != null) hashBytes += data.length();
        }
        long hashTime = System.nanoTime() - hashStart;

        // --- 新方案：Sorted Set (ZRANGEBYSCORE) ---
        TreeMap<Long, String> sortedStore = new TreeMap<>();
        IntStream.rangeClosed(1, EVENT_COUNT).forEach(i ->
                sortedStore.put((long) i, "data_" + i));

        long sortedStart = System.nanoTime();
        // Step 1: ZRANGEBYSCORE — 范围查询（直接从 lastEventId + 1 开始）
        Map<Long, String> pending = sortedStore.tailMap((long) lastEventId + 1);
        // Step 2: 直接遍历（已排序）
        int sortedBytes = 0;
        for (Map.Entry<Long, String> entry : pending.entrySet()) {
            sortedBytes += entry.getValue().length();
        }
        long sortedTime = System.nanoTime() - sortedStart;

        // --- 结果 ---
        System.out.println("===== SSE 续传对比 (N=" + EVENT_COUNT + ", 待补发=1000) =====");
        System.out.printf("旧方案(Hash HGETALL+遍历): %d ns (%.2f ms)%n", hashTime, hashTime / 1_000_000.0);
        System.out.printf("新方案(Sorted Set ZRANGEBYSCORE): %d ns (%.2f ms)%n", sortedTime, sortedTime / 1_000_000.0);
        System.out.printf("加速比: %.1fx%n", (double) hashTime / sortedTime);
        System.out.printf("读取的数据量: %d bytes (两者一致)%n%n", hashBytes);

        assertTrue(sortedTime < hashTime, "Sorted Set 应比 Hash 快");
        assertEquals(hashBytes, sortedBytes, "两种方式读取的数据量应一致");
    }

    // ==================== 2. SSE 续传：不同缓冲区大小对比 ====================

    @Test
    @DisplayName("不同缓冲区大小下 Hash vs Sorted Set 性能趋势")
    void sseResumeScaling() {
        System.out.println("===== 缓冲区大小扩展性对比 =====");
        System.out.printf("%-10s %-20s %-20s %-10s%n", "事件总数", "Hash耗时(ns)", "ZSet耗时(ns)", "加速比");

        for (int n : new int[]{100, 1000, 10000, 50000}) {
            int lastEventId = (int) (n * 0.9); // 90% 已消费，10% 待补发

            // Hash
            Map<String, String> hash = new HashMap<>();
            IntStream.rangeClosed(1, n).forEach(i -> hash.put(String.valueOf(i), "d"));
            long t1 = System.nanoTime();
            List<Long> seqs = new ArrayList<>();
            for (String k : hash.keySet()) { long s = Long.parseLong(k); if (s > lastEventId) seqs.add(s); }
            Collections.sort(seqs);
            for (long s : seqs) hash.get(String.valueOf(s));
            long hashTime = System.nanoTime() - t1;

            // Sorted Set
            TreeMap<Long, String> zset = new TreeMap<>();
            IntStream.rangeClosed(1, n).forEach(i -> zset.put((long) i, "d"));
            long t2 = System.nanoTime();
            Map<Long, String> tail = zset.tailMap((long) lastEventId + 1);
            for (Map.Entry<Long, String> e : tail.entrySet()) e.getValue();
            long zsetTime = System.nanoTime() - t2;

            double ratio = (double) hashTime / Math.max(zsetTime, 1);
            System.out.printf("%-10d %-20d %-20d %.1fx%n", n, hashTime, zsetTime, ratio);
            assertTrue(zsetTime < hashTime || n <= 1000,
                    String.format("N=%d 时 ZSet 应快于 Hash", n));
        }
        System.out.println();
    }

    // ==================== 3. 会话级流控：防刷屏效果 ====================

    @Test
    @DisplayName("会话级流控 L2: 单会话多请求防刷屏")
    void sessionFlowControl() {
        ConcurrentHashMap<String, AtomicInteger> concurrency = new ConcurrentHashMap<>();
        int maxConcurrent = 1;
        String sessionId = "session-001";

        // 模拟同一会话发送 5 个并发请求
        AtomicInteger accepted = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        IntStream.range(0, 5).parallel().forEach(i -> {
            AtomicInteger counter = concurrency.computeIfAbsent(
                    sessionId, k -> new AtomicInteger(0));
            int current = counter.incrementAndGet();
            if (current <= maxConcurrent) {
                accepted.incrementAndGet();
                // 模拟处理中
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                counter.decrementAndGet();
                if (counter.get() <= 0) concurrency.remove(sessionId);
            } else {
                counter.decrementAndGet();
                rejected.incrementAndGet();
            }
        });

        System.out.println("===== 会话级流控 L2 =====");
        System.out.printf("maxConcurrent=%d, 并发请求数=5%n", maxConcurrent);
        System.out.printf("接受: %d, 拒绝: %d%n", accepted.get(), rejected.get());
        System.out.println("效果: 同一会话最多 1 个请求在处理, 其余排队\n");

        assertTrue(accepted.get() >= 1, "至少应接受 1 个请求");
        assertTrue(rejected.get() >= 4, "至少应拒绝 4 个请求(超过 maxConcurrent)");
    }

    // ==================== 4. 优先级调度：VIP 优先 ====================

    @Test
    @DisplayName("L3 优先级队列: VIP 优先于普通用户")
    void priorityQueue() {
        // 模拟 PriorityBlockingQueue（用 TreeSet 模拟）
        TreeSet<PrioritizedRequest> queue = new TreeSet<>(
                (a, b) -> {
                    int cmp = Integer.compare(a.priority, b.priority);
                    return cmp != 0 ? cmp : Long.compare(a.enqueueTime, b.enqueueTime);
                });

        // 插入: 普通用户(vip=false, priority=1) 先入队，但 VIP(priority=0) 后入队
        long now = System.nanoTime();
        queue.add(new PrioritizedRequest("req-001", 1, now));     // 普通，先入队
        queue.add(new PrioritizedRequest("req-002", 0, now + 1)); // VIP，后入队
        queue.add(new PrioritizedRequest("req-003", 1, now + 2)); // 普通，后入队
        queue.add(new PrioritizedRequest("req-004", 0, now + 3)); // VIP，后入队

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            order.add(queue.pollFirst().requestId);
        }

        System.out.println("===== L3 优先级调度 =====");
        System.out.println("入队顺序: req-001(普通), req-002(VIP), req-003(普通), req-004(VIP)");
        System.out.println("出队顺序: " + String.join(" → ", order));
        System.out.println("效果: VIP(priority=0) 先于 普通(priority=1) 出队\n");

        assertEquals("req-002", order.get(0), "VIP 优先出队");
        assertEquals("req-004", order.get(1), "VIP 优先出队");
    }

    private record PrioritizedRequest(String requestId, int priority, long enqueueTime) {}
}
