package com.example.smartassistant.benchmark;

import com.example.smartassistant.entity.ApprovalRecordEntity;
import com.example.smartassistant.entity.ApprovalRecordEntity.ApprovalStatus;
import com.example.smartassistant.entity.ApprovalStateException;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApprovalRecordEntity 状态机性能与并发安全基准测试。
 * <p>
 * 测试改前后的差异对比：
 * <ul>
 *   <li>改造前：贫血 POJO，状态转换逻辑散落在 ApprovalService 的方法中</li>
 *   <li>改造后：充血状态机，状态转换逻辑内聚到实体内部，终态不可变</li>
 * </ul>
 * </p>
 *
 * <h3>关键指标</h3>
 * <ul>
 *   <li>终态不可变性：CONSUMED/CANCELLED 状态不应再被转换</li>
 *   <li>并发安全：200 线程同时尝试 confirm()，仅 1 次成功</li>
 *   <li>状态转换合规性：非法转换（如 PENDING→CANCELLED 的正确性）</li>
 * </ul>
 */
@DisplayName("[Benchmark] ApprovalRecordEntity 状态机测试")
class ApprovalStateMachineBenchmarkTest {

    // ==================== 测试 1：终态不可变 ====================

    @Test
    @DisplayName("终态不可变：CONSUMED 无法被转换")
    void testConsumedStateIsTerminal() {
        var entity = createEntity();
        entity.setStatus(ApprovalStatus.CONSUMED.name());

        assertThrows(ApprovalStateException.class, () -> entity.confirm("admin", "127.0.0.1"),
                "终态 CONSUMED 不能再被 confirm()");
        assertThrows(ApprovalStateException.class, () -> entity.cancel(),
                "终态 CONSUMED 不能再被 cancel()");
        assertThrows(ApprovalStateException.class, () -> entity.consume(),
                "终态 CONSUMED 不能再被 consume()");
    }

    @Test
    @DisplayName("终态不可变：CANCELLED 无法被转换")
    void testCancelledStateIsTerminal() {
        var entity = createEntity();
        entity.cancel();  // PENDING → CANCELLED

        assertThrows(ApprovalStateException.class, () -> entity.confirm("admin", "127.0.0.1"),
                "终态 CANCELLED 不能再被 confirm()");
        assertThrows(ApprovalStateException.class, () -> entity.consume(),
                "终态 CANCELLED 不能再被 consume()");
        assertThrows(ApprovalStateException.class, () -> entity.cancel(),
                "终态 CANCELLED 不能再被 cancel()");
    }

    // ==================== 测试 2：标准化状态转换 ====================

    @Test
    @DisplayName("标准流程：PENDING → CONFIRMED → CONSUMED")
    void testHappyPath() {
        var entity = createEntity();

        entity.confirm("admin", "127.0.0.1");
        assertEquals(ApprovalStatus.CONFIRMED.name(), entity.getStatus());
        assertEquals("admin", entity.getOperator());
        assertEquals("127.0.0.1", entity.getOperatorIp());
        assertNotNull(entity.getConfirmedAt());

        entity.consume();
        assertEquals(ApprovalStatus.CONSUMED.name(), entity.getStatus());
        assertNotNull(entity.getConsumedAt());
    }

    @Test
    @DisplayName("取消流程：PENDING → CANCELLED")
    void testCancelPath() {
        var entity = createEntity();

        entity.cancel();
        assertEquals(ApprovalStatus.CANCELLED.name(), entity.getStatus());
    }

    // ==================== 测试 3：非法转换拒绝 ====================

    @Test
    @DisplayName("非法转换：PENDING 不能直接 consume()")
    void testCannotConsumeFromPending() {
        var entity = createEntity();
        assertThrows(ApprovalStateException.class, entity::consume,
                "PENDING 状态不能直接 consume()");
    }

    @Test
    @DisplayName("非法转换：CANCELLED 不能再转换")
    void testCancelledCannotTransition() {
        var entity = createEntity();
        entity.cancel();

        assertThrows(ApprovalStateException.class,
                () -> entity.confirm("admin", "127.0.0.1"));
        assertThrows(ApprovalStateException.class, entity::consume);
    }

    @Test
    @DisplayName("非法转换：CONFIRMED 不能 cancel()")
    void testConfirmedCannotCancel() {
        var entity = createEntity();
        entity.confirm("admin", "127.0.0.1");

        assertThrows(ApprovalStateException.class, entity::cancel,
                "CONFIRMED 状态下不能取消");
    }

    // ==================== 测试 4：并发安全 ====================

    @Test
    @DisplayName("并发安全：200 线程同时 confirm()，仅 1 次成功")
    void testConcurrentConfirmSafety() throws InterruptedException {
        int threadCount = 200;
        var entity = createEntity();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // 使用 CountDownLatch 让所有线程同时启动
        CountDownLatch latch = new CountDownLatch(1);
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                try {
                    latch.await(); // 所有线程等待
                    entity.confirm("user-" + id, "10.0.0." + (id % 255));
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ApprovalStateException e) {
                    failureCount.incrementAndGet();
                }
            });
            threads[i].start();
        }

        latch.countDown(); // 同时释放所有线程

        for (Thread t : threads) {
            t.join(5000); // 最多等 5 秒
        }

        assertEquals(1, successCount.get(),
                "200 线程并发 confirm() 应仅有 1 次成功");
        assertEquals(threadCount - 1, failureCount.get(),
                "其余 199 线程应抛出 ApprovalStateException");
        assertEquals(ApprovalStatus.CONFIRMED.name(), entity.getStatus(),
                "最终状态为 CONFIRMED");
    }

    // ==================== 测试 5：并发 consume 安全 ====================

    @Test
    @DisplayName("并发安全：100 线程同时 consume()，仅 1 次成功")
    void testConcurrentConsumeSafety() throws InterruptedException {
        int threadCount = 100;
        var entity = createEntity();
        entity.confirm("admin", "10.0.0.1"); // 先 confirm

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                try {
                    latch.await();
                    entity.consume();
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ApprovalStateException e) {
                    failureCount.incrementAndGet();
                }
            });
            threads[i].start();
        }

        latch.countDown();

        for (Thread t : threads) {
            t.join(5000);
        }

        assertEquals(1, successCount.get(),
                "100 线程并发 consume() 应仅有 1 次成功");
        assertEquals(threadCount - 1, failureCount.get(),
                "其余 99 线程应抛出 ApprovalStateException");
        assertEquals(ApprovalStatus.CONSUMED.name(), entity.getStatus(),
                "最终状态为 CONSUMED");
    }

    // ==================== 测试 6：延迟基准 ====================

    @Test
    @DisplayName("性能基准：状态转换延迟")
    void testStateTransitionLatency() {
        int iterations = 10000;
        long totalNanos = 0;
        long minNanos = Long.MAX_VALUE;
        long maxNanos = 0;

        for (int i = 0; i < iterations; i++) {
            var entity = createEntity();

            long start = System.nanoTime();
            entity.confirm("admin", "10.0.0.1");
            entity.consume();
            long end = System.nanoTime();

            long latency = end - start;
            totalNanos += latency;
            minNanos = Math.min(minNanos, latency);
            maxNanos = Math.max(maxNanos, latency);
        }

        double avgUs = (double) totalNanos / iterations / 1_000;
        double minUs = (double) minNanos / 1_000;
        double maxUs = (double) maxNanos / 1_000;

        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  状态机转换延迟基准");
        System.out.println("═══════════════════════════════════════════════");
        System.out.printf("  测试次数: %d 次 (PENDING→CONFIRMED→CONSUMED)\n", iterations);
        System.out.println("───────────────────────────────────────────────");
        System.out.printf("  平均延迟: %.2f μs\n", avgUs);
        System.out.printf("  最小延迟: %.2f μs\n", minUs);
        System.out.printf("  最大延迟: %.2f μs\n", maxUs);
        System.out.printf("  吞吐量:   %.0f 次/秒\n", iterations / (totalNanos / 1_000_000_000.0));
        System.out.println("═══════════════════════════════════════════════\n");

        assertTrue(avgUs <= 50, "单次状态机转换延迟应 ≤ 50μs");
    }

    // ==================== 测试 7：null 安全检查 ====================

    @Test
    @DisplayName("null 安全检查：operator 和 operatorIp 不能为 null")
    void testNullSafety() {
        var entity = createEntity();

        assertThrows(IllegalArgumentException.class,
                () -> entity.confirm(null, "10.0.0.1"),
                "operator 不能为 null");
        assertThrows(IllegalArgumentException.class,
                () -> entity.confirm("admin", null),
                "operatorIp 不能为 null");
        assertThrows(IllegalArgumentException.class,
                () -> entity.confirm("", "10.0.0.1"),
                "operator 不能为空字符串");
    }

    // ==================== 辅助方法 ====================

    private ApprovalRecordEntity createEntity() {
        ApprovalRecordEntity entity = new ApprovalRecordEntity();
        entity.setId(1L);
        entity.setOrderId("ORD-TEST-001");
        entity.setActionType("REFUND");
        entity.setReason("测试退款");
        entity.setStatus(ApprovalStatus.PENDING.name());
        entity.setCreatedAt(java.time.LocalDateTime.now());
        return entity;
    }
}
