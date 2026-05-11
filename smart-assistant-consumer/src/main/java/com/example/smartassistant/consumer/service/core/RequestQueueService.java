package com.example.smartassistant.consumer.service.core;

import com.example.smartassistant.consumer.config.ChatQueueConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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

    // ⭐ LLM 并发槽位 — 公平模式确保等待较久的线程优先获取
    private final Semaphore slots;

    // ⭐ 等待队列（FIFO，仅用于跟踪位置）
    private final LinkedList<QueuedRequest> waitingQueue = new LinkedList<>();

    // ⭐ requestId → 等待中的请求（用于查询位置和取消）
    private final Map<String, QueuedRequest> requestMap = new ConcurrentHashMap<>();

    private final ChatQueueConfig config;

    public RequestQueueService(ChatQueueConfig config) {
        this.config = config;
        this.slots = new Semaphore(config.getMaxConcurrent(), true); // 公平模式
        log.info("[Queue] 初始化: maxConcurrent={}, maxQueueSize={}, queueTimeoutMs={}",
                config.getMaxConcurrent(), config.getMaxQueueSize(), config.getQueueTimeoutMs());
    }

    /**
     * 尝试获取 LLM 槽位或加入排队队列
     * <p>
     * - {@link SlotResult#ACQUIRED}: 有槽位，可以立即处理
     * - {@link SlotResult#QUEUED}: 进入排队队列
     * - {@link SlotResult#QUEUE_FULL}: 队列已满，拒绝请求
     */
    public SlotResult tryAcquireWithQueue(String requestId) {
        // 1. 先尝试非阻塞获取槽位
        if (slots.tryAcquire()) {
            log.debug("[Queue] ✅ 立即获取槽位: requestId={}", requestId);
            return SlotResult.ACQUIRED;
        }

        // 2. 检查队列是否已满
        if (waitingQueue.size() >= config.getMaxQueueSize()) {
            log.warn("[Queue] ❌ 队列已满: requestId={}, queueSize={}", requestId, waitingQueue.size());
            return SlotResult.QUEUE_FULL;
        }

        // 3. 加入等待队列
        QueuedRequest queued = new QueuedRequest(requestId, System.currentTimeMillis());
        synchronized (waitingQueue) {
            // ⭐ 二次检查（在获取锁期间可能槽位被释放）
            if (slots.tryAcquire()) {
                log.debug("[Queue] ✅ 二次检查获取槽位成功: requestId={}", requestId);
                return SlotResult.ACQUIRED;
            }
            queued.position = waitingQueue.size() + 1;
            waitingQueue.addLast(queued);
            requestMap.put(requestId, queued);
        }

        log.info("[Queue] ⏳ 进入排队: requestId={}, position={}", requestId, queued.position);
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
     * 完成处理，释放 LLM 槽位
     * <p>
     * 释放 Semaphore 许可证，自动唤醒 Semaphore 等待队列中的下一个线程。
     */
    public void complete(String requestId) {
        requestMap.remove(requestId);
        slots.release();
        log.debug("[Queue] 释放槽位(已排队={})", waitingQueue.size());
    }

    /**
     * 获取等待队列中指定 requestId 的位置（1-based）
     */
    public int getQueuePosition(String requestId) {
        QueuedRequest queued = requestMap.get(requestId);
        if (queued == null) return 0;
        synchronized (waitingQueue) {
            int pos = 1;
            for (QueuedRequest q : waitingQueue) {
                if (q.requestId.equals(requestId)) return pos;
                pos++;
            }
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
        synchronized (waitingQueue) {
            Iterator<QueuedRequest> it = waitingQueue.iterator();
            while (it.hasNext()) {
                if (it.next().requestId.equals(requestId)) {
                    it.remove();
                    break;
                }
            }
        }
        requestMap.remove(requestId);
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
     * 等待队列中的请求
     */
    private static class QueuedRequest {
        final String requestId;
        final long enqueueTime;
        volatile int position;

        QueuedRequest(String requestId, long enqueueTime) {
            this.requestId = requestId;
            this.enqueueTime = enqueueTime;
        }
    }
}
