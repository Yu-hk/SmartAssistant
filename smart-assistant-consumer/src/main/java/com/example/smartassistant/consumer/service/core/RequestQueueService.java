/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.core;

import com.example.smartassistant.consumer.config.ChatQueueConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 请求排队服务
 * <p>
 * 限制 LLM 并发槽位数，超出时自动排队等待。
 * 适用于 StreamChatController 的 SSE 长连接，在转发 Agent SSE 前排队。
 * <p>
 * 核心机制：
 * 1. Semaphore 控制 LLM 并发槽位（默认 5）
 * 2. FIFO 队列跟踪等待顺序
 * 3. 等待线程通过 Semaphore.tryAcquire(timeout) 阻塞等待槽位
 */
@Service
public class RequestQueueService {

    private static final Logger log = LoggerFactory.getLogger(RequestQueueService.class);

    // ═══════════════════════════════════════════════════════════
    // ⭐ 优先级常量（值越小优先级越高，对应多级优先级队列设计）
    // ═══════════════════════════════════════════════════════════

    /** 实时对话（最高优先级）：通用对话、简单查询，需低延迟保障 */
    public static final int PRIORITY_REALTIME = 10;

    /** 普通任务（中优先级）：订单查询、商品推荐等业务请求 */
    public static final int PRIORITY_NORMAL = 5;

    /** 离线批量（最低优先级）：数据分析、批量处理等闲时任务 */
    public static final int PRIORITY_BATCH = 0;

    // ⭐ LLM 并发槽位 — 公平模式确保等待较久的线程优先获取
    private final Semaphore slots;

    // ⭐ 等待队列（FIFO 或优先级，默认 FIFO）
    private final PriorityBlockingQueue<QueuedRequest> waitingQueue;

    // ⭐ requestId → 等待中的请求（用于查询位置和取消）
    private final Map<String, QueuedRequest> requestMap = new ConcurrentHashMap<>();

    // ⭐ L2: 会话级并发控制 — 每 sessionId 当前并发请求数
    private final ConcurrentHashMap<String, AtomicInteger> sessionConcurrency = new ConcurrentHashMap<>();

    // ⭐ L2: 每 session 最大并发数
    @Value("${queue.session-max-concurrency:1}")
    private int sessionMaxConcurrency;

    // ⭐ L3: 是否启用优先级队列（默认 true，REALTIME/NORMAL/BATCH 三级）
    @Value("${queue.priority-enabled:true}")
    private boolean priorityEnabled;

    private final ChatQueueConfig config;

    public RequestQueueService(ChatQueueConfig config) {
        this.config = config;
        this.slots = new Semaphore(config.getMaxConcurrent(), true); // 公平模式
        // ⭐ L3: 优先级队列（priority 低值优先）或 FIFO（按插入时间排序）
        this.waitingQueue = new PriorityBlockingQueue<>(1000, (a, b) -> {
            if (priorityEnabled) {
                int cmp = Integer.compare(a.priority, b.priority);
                return cmp != 0 ? cmp : Long.compare(a.enqueueTime, b.enqueueTime);
            }
            return Long.compare(a.enqueueTime, b.enqueueTime);
        });
        log.info("[Queue] 初始化: maxConcurrent={}, maxQueueSize={}, queueTimeoutMs={}, sessionMaxConcurrency={}, priorityEnabled={}",
                config.getMaxConcurrent(), config.getMaxQueueSize(), config.getQueueTimeoutMs(),
                sessionMaxConcurrency, priorityEnabled);
    }

    /**
     * 尝试获取 LLM 槽位或加入排队队列。
     * <p>
     * L1: 全局 Semaphore 并发控制<br>
     * L2: 会话级并发控制（每 sessionId 最多 {@link #sessionMaxConcurrency}）<br>
     * L3: 优先级队列（可选）
     * </p>
     */
    public SlotResult tryAcquireWithQueue(String requestId) {
        return tryAcquireWithQueue(requestId, null, 0);
    }

    /**
     * 尝试获取 LLM 槽位或加入排队队列（含会话级流控和优先级）。
     *
     * @param requestId  请求 ID
     * @param sessionId  会话 ID（用于 L2 会话级限流，null 跳过）
     * @param priority   优先级（越小越优先，L3 启用时有效）
     */
    public SlotResult tryAcquireWithQueue(String requestId, String sessionId, int priority) {
        // ⭐ L2: 会话级并发控制
        if (sessionId != null && !sessionId.isBlank()) {
            if (!tryAcquireSessionSlot(sessionId)) {
                log.warn("[Queue] ❌ 会话并发超限: sessionId={}, max={}", sessionId, sessionMaxConcurrency);
                return SlotResult.QUEUE_FULL; // 同一会话已有请求在处理
            }
        }

        // 1. 先尝试非阻塞获取槽位
        if (slots.tryAcquire()) {
            log.debug("[Queue] ✅ 立即获取槽位: requestId={}", requestId);
            return SlotResult.ACQUIRED;
        }

        // 2. 检查队列是否已满
        if (waitingQueue.size() >= config.getMaxQueueSize()) {
            // L2: 释放之前加上的会话槽位
            if (sessionId != null && !sessionId.isBlank()) releaseSessionSlot(sessionId);
            log.warn("[Queue] ❌ 队列已满: requestId={}, queueSize={}", requestId, waitingQueue.size());
            return SlotResult.QUEUE_FULL;
        }

        // 3. 加入等待队列（PriorityBlockingQueue 自动按优先级+时间排序）
        QueuedRequest queued = new QueuedRequest(requestId, sessionId, priority, System.currentTimeMillis());
        // ⭐ 二次检查（在加锁期间可能槽位被释放）
        if (slots.tryAcquire()) {
            if (sessionId != null && !sessionId.isBlank()) releaseSessionSlot(sessionId);
            log.debug("[Queue] ✅ 二次检查获取槽位成功: requestId={}", requestId);
            return SlotResult.ACQUIRED;
        }
        queued.position = waitingQueue.size() + 1;
        waitingQueue.add(queued);
        requestMap.put(requestId, queued);

        log.info("[Queue] ⏳ 进入排队: requestId={}, position={}, priority={}", requestId, queued.position, priority);
        return SlotResult.QUEUED;
    }

    /**
     * 阻塞等待 LLM 槽位
     * <p>
     * 内部通过 Semaphore.tryAcquire(timeout) 实现，当有槽位释放时自动获取。
     *
     * @return true 获取到槽位；false 排队超时
     */
    public boolean waitForSlot(String requestId) {
        QueuedRequest queued = requestMap.get(requestId);
        if (queued == null) {
            log.warn("[Queue] requestId 不在等待队列中: {}", requestId);
            return false;
        }

        try {
            boolean acquired = slots.tryAcquire(config.getQueueTimeoutMs(), TimeUnit.MILLISECONDS);
            if (acquired) {
                log.info("[Queue] ✅ 槽位获取成功(排队后): requestId={}, waitedMs={}",
                        requestId, System.currentTimeMillis() - queued.enqueueTime);
                requestMap.remove(requestId);
                return true;
            }
            // 超时
            log.warn("[Queue] ⏰ 排队超时: requestId={}, waitedMs={}",
                    requestId, System.currentTimeMillis() - queued.enqueueTime);
            removeFromQueue(requestId);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            removeFromQueue(requestId);
            return false;
        }
    }

    /**
     * 完成处理，释放 LLM 槽位和会话槽位。
     */
    public void complete(String requestId) {
        QueuedRequest queued = requestMap.remove(requestId);
        if (queued != null && queued.sessionId != null) {
            releaseSessionSlot(queued.sessionId);
        }
        slots.release();
        log.debug("[Queue] 释放槽位(已排队={})", waitingQueue.size());
    }

    /**
     * 获取等待队列中指定 requestId 的位置（1-based）
     */
    public int getQueuePosition(String requestId) {
        QueuedRequest queued = requestMap.get(requestId);
        if (queued == null) return 0;
        int pos = 1;
        for (QueuedRequest q : waitingQueue) {
            if (q.requestId.equals(requestId)) return pos;
            pos++;
        }
        return 0;
    }

    /**
     * 获取当前等待请求数
     */
    public int getQueueSize() {
        synchronized (waitingQueue) {
            return waitingQueue.size();
        }
    }

    /**
     * 获取当前并发数（已占用槽位数）
     */
    public int getActiveCount() {
        return config.getMaxConcurrent() - slots.availablePermits();
    }

    /**
     * 从等待队列中移除指定 requestId
     */
    private void removeFromQueue(String requestId) {
        for (QueuedRequest q : waitingQueue) {
            if (q.requestId.equals(requestId)) {
                waitingQueue.remove(q);
                if (q.sessionId != null) releaseSessionSlot(q.sessionId);
                break;
            }
        }
        requestMap.remove(requestId);
    }

    /**
     * ⭐ L2: 尝试获取会话级并发槽位。
     *
     * @return true 表示可以处理该会话的请求
     */
    boolean tryAcquireSessionSlot(String sessionId) {
        AtomicInteger counter = sessionConcurrency.computeIfAbsent(
                sessionId, k -> new AtomicInteger(0));
        int current = counter.incrementAndGet();
        if (current > sessionMaxConcurrency) {
            counter.decrementAndGet();
            if (current - 1 <= 0) sessionConcurrency.remove(sessionId);
            return false;
        }
        return true;
    }

    /**
     * ⭐ L2: 释放会话级并发槽位。
     */
    void releaseSessionSlot(String sessionId) {
        AtomicInteger counter = sessionConcurrency.get(sessionId);
        if (counter != null) {
            int remaining = counter.decrementAndGet();
            if (remaining <= 0) {
                sessionConcurrency.remove(sessionId);
            }
        }
    }

    /**
     * ⭐ L2: 获取指定会话的当前并发数。
     */
    public int getSessionConcurrency(String sessionId) {
        AtomicInteger counter = sessionConcurrency.get(sessionId);
        return counter != null ? counter.get() : 0;
    }

    // ==================== 内部类 ====================

    /**
     * 槽位获取结果
     */
    public enum SlotResult {
        /** 立即获取到槽位 */
        ACQUIRED,
        /** 加入排队队列 */
        QUEUED,
        /** 队列已满 */
        QUEUE_FULL
    }

    /**
     * 等待队列中的请求（含会话 ID 和优先级）
     */
    private static class QueuedRequest {
        final String requestId;
        final String sessionId;
        final int priority;
        final long enqueueTime;
        volatile int position;

        QueuedRequest(String requestId, String sessionId, int priority, long enqueueTime) {
            this.requestId = requestId;
            this.sessionId = sessionId;
            this.priority = priority;
            this.enqueueTime = enqueueTime;
        }
    }
}
