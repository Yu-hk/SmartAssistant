/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.sse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 事件总线——发送和缓存 SSE 事件。
 *
 * <p>封装了：
 * <ul>
 *   <li>向 {@link HttpServletResponse} 写入 SSE 事件</li>
 *   <li>Redis Sorted Set 缓存（用于断线续传）</li>
 *   <li>代理 SSE 流的转发与事件 ID 注入</li>
 *   <li>心跳检测 + 闲置超时释放</li>
 * </ul>
 */
public class SseEventBus {

    private static final Logger log = LoggerFactory.getLogger(SseEventBus.class);

    /** ⭐ SSE 事件缓冲计数器——每缓存一条代理流事件 +1，用于观测断线续传缓冲负载 */
    private static final Counter SSE_BUFFER_COUNTER = Counter.builder("a2a_sse_events_buffered_total")
            .description("写入 Redis 断线续传缓冲区的 SSE 事件总数")
            .register(Metrics.globalRegistry);

    /** Redis ZSet 缓存前缀 */
    public static final String SSE_BUFFER_PREFIX = "sse:buffer:";
    /** 缓存 TTL（秒） */
    public static final long SSE_BUFFER_TTL_SECONDS = 300;

    /** 心跳间隔（毫秒） */
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;
    /** 闲置超时（毫秒）— 超过此时间无事件发送则自动关闭 */
    private static final long IDLE_TIMEOUT_MS = 60_000;

    /** ⭐ 每个缓冲区的最大事件数上限 —— 超过后停止缓存（防 Redis 内存溢出） */
    private static final int MAX_EVENTS_PER_BUFFER = 10_000;

    private final HttpServletResponse response;
    private final String redisKey;
    private final RedisZSetCache redisCache;
    private long seqNo = 1;
    private volatile boolean closed = false;

    /** ⭐ 当前缓冲区已缓存事件数（用于上限保护） */
    private int eventsCached = 0;

    /** 上次发送事件的时间戳 */
    private final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());

    /** 心跳定时任务 */
    private ScheduledFuture<?> heartbeatFuture;

    public SseEventBus(HttpServletResponse response, String requestId, RedisZSetCache redisCache) {
        this.response = response;
        this.redisKey = requestId != null ? SSE_BUFFER_PREFIX + requestId : null;
        this.redisCache = redisCache;
        initResponse();
        startHeartbeat();
    }

    /** 无缓存的构造 */
    public SseEventBus(HttpServletResponse response) {
        this(response, null, null);
    }

    // ==================== 生命周期 ====================

    private void initResponse() {
        try {
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            response.flushBuffer();
        } catch (Exception e) {
            log.warn("[SseEventBus] 初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 启动心跳线程。
     * 每 15s 发送一次 comment 行保持连接，超过 60s 无事件则自动关闭。
     */
    private void startHeartbeat() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (closed || response.isCommitted()) {
                    close();
                    return;
                }
                long idle = System.currentTimeMillis() - lastActivityTime.get();
                if (idle > IDLE_TIMEOUT_MS) {
                    log.info("[SseEventBus] 闲置超时 ({}ms)，关闭连接", idle);
                    close();
                    return;
                }
                // ⭐ 发送心跳 comment 行（保持连接活跃）
                synchronized (this) {
                    response.getOutputStream().write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                    response.getOutputStream().flush();
                }
            } catch (Exception e) {
                log.debug("[SseEventBus] 心跳异常，连接可能已断开: {}", e.getMessage());
                close();
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 主动关闭连接，释放资源。
     */
    public void close() {
        if (closed) return;
        closed = true;
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        try { response.getOutputStream().close(); } catch (Exception e) { log.debug("[SseEventBus] 关闭输出流: {}", e.getMessage()); }
    }

    // ==================== 发送事件 ====================

    /**
     * 发送一个 SSE 事件。
     */
    public synchronized void send(SseEvent event) {
        if (closed || response.isCommitted()) return;
        try {
            String idLine = "id: " + seqNo + "\n";
            response.getOutputStream().write(idLine.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().write(event.render().getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();

            cacheEvent(event);
            seqNo++;
            lastActivityTime.set(System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("[SseEventBus] 发送事件失败: {}", e.getMessage());
            close();
        }
    }

    /**
     * 发送预定义事件（简化版）。
     */
    public void sendWaiting() { send(SseEvent.waiting()); }
    public void sendProcessing() { send(SseEvent.processing()); }
    public void sendDone() { send(SseEvent.done()); }
    public void sendError(String message) { send(SseEvent.error(message)); }
    public void sendTimeout(String message) { send(SseEvent.timeout(message)); }
    public void sendQueue(int pos, long est) { send(SseEvent.queue(pos, est)); }
    public void sendQueuePosition(int pos, long est) { send(SseEvent.queuePosition(pos, est)); }
    public void sendRouted(String agent, double confidence) { send(SseEvent.routed(agent, confidence)); }

    // ==================== 断线续传 ====================

    /**
     * 从 Redis 补发历史事件。
     *
     * @param lastEventId 前端最后收到的事件 ID
     * @return 是否补发了事件
     */
    public boolean resume(long lastEventId) {
        if (redisKey == null || redisCache == null) return false;
        try {
            Set<String> pending = redisCache.rangeByScore(
                    redisKey, lastEventId + 1, Long.MAX_VALUE);
            if (pending == null || pending.isEmpty()) return false;

            log.info("[SseEventBus] 断线续传: lastEventId={}, 补发={} 条", lastEventId, pending.size());
            long currentSeq = lastEventId + 1;
            for (String data : pending) {
                String idLine = "id: " + currentSeq + "\n";
                response.getOutputStream().write(idLine.getBytes(StandardCharsets.UTF_8));
                response.getOutputStream().write("data: ".getBytes(StandardCharsets.UTF_8));
                response.getOutputStream().write(data.getBytes(StandardCharsets.UTF_8));
                response.getOutputStream().write("\n\n".getBytes(StandardCharsets.UTF_8));
                response.getOutputStream().flush();
                currentSeq++;
            }
            this.seqNo = currentSeq;
            return true;
        } catch (Exception e) {
            log.warn("[SseEventBus] 续传异常: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 代理 SSE 流 ====================

    /**
     * 代理转发外部 SSE 流，注入事件 ID 并缓存。
     */
    public void forwardStream(HttpURLConnection connection) {
        try (InputStream is = connection.getInputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {

            StringBuilder currentEvent = new StringBuilder();
            boolean hasData = false;
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (hasData && currentEvent.length() > 0) {
                        // 解析为 SseEvent 并发送
                        SseEvent event = SseEvent.create();
                        String data = null;
                        String eventType = null;
                        for (String l : currentEvent.toString().split("\n")) {
                            if (l.startsWith("data:")) data = l.substring(5).trim();
                            else if (l.startsWith("event:")) eventType = l.substring(6).trim();
                        }
                        if (eventType != null) event.event(eventType);
                        if (data != null) event.data(data);
                        send(event);
                    }
                    currentEvent = new StringBuilder();
                    hasData = false;
                } else {
                    currentEvent.append(line).append("\n");
                    if (line.startsWith("data:")) hasData = true;
                }
            }
        } catch (Exception e) {
            log.warn("[SseEventBus] 代理流异常: {}", e.getMessage());
        }
    }

    // ==================== 缓存管理 ====================

    private void cacheEvent(SseEvent event) {
        if (redisKey == null || redisCache == null) return;
        if (eventsCached >= MAX_EVENTS_PER_BUFFER) {
            if (eventsCached == MAX_EVENTS_PER_BUFFER) {
                log.warn("[SseEventBus] 缓冲区已达上限 ({}), 停止缓存: requestId={}",
                        MAX_EVENTS_PER_BUFFER, redisKey);
                eventsCached++;
            }
            return;
        }
        try {
            String data = extractData(event.render());
            if (data != null) {
                redisCache.add(redisKey, data, seqNo);
                redisCache.expire(redisKey, SSE_BUFFER_TTL_SECONDS, TimeUnit.SECONDS);
                eventsCached++;
                SSE_BUFFER_COUNTER.increment();
            }
        } catch (Exception e) {
            log.debug("[SseEventBus] 缓存失败: seqNo={}", seqNo);
        }
    }

    private static String extractData(String sseText) {
        if (sseText == null) return null;
        for (String line : sseText.split("\n")) {
            if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                return data.isEmpty() ? null : data;
            }
        }
        return null;
    }

    public long currentSeqNo() { return seqNo; }
    public boolean isClosed() { return closed; }

    // ═══════════════════════════════════════════════════════════
    // Redis ZSet 缓存接口（解耦 StringRedisTemplate 依赖）
    // ═══════════════════════════════════════════════════════════

    @FunctionalInterface
    public interface RedisZSetCache {
        void add(String key, String value, double score);
        default Set<String> rangeByScore(String key, long min, long max) { return Set.of(); }
        default void expire(String key, long timeout, TimeUnit unit) {}
    }
}
