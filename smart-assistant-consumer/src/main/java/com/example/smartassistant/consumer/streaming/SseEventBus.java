/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.streaming;

import com.example.smartassistant.common.security.PiiPolicyEngine;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern JSON_EVENT_TYPE = Pattern.compile(
            "\\\"type\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

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
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 60_000;

    /** ⭐ 每个缓冲区的最大事件数上限 —— 超过后停止缓存（防 Redis 内存溢出） */
    private static final int MAX_EVENTS_PER_BUFFER = 10_000;

    private final HttpServletResponse response;
    private final String redisKey;
    private final RedisZSetCache redisCache;
    private final long idleTimeoutMs;
    private final PiiPolicyEngine piiPolicyEngine;
    private long seqNo = 1;
    private volatile boolean closed = false;

    /** ⭐ 当前缓冲区已缓存事件数（用于上限保护） */
    private int eventsCached = 0;

    /** 上次发送事件的时间戳 */
    private final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());

    /** 心跳定时任务 */
    private ScheduledFuture<?> heartbeatFuture;

    public SseEventBus(HttpServletResponse response, String requestId, RedisZSetCache redisCache) {
        this(response, requestId, redisCache, DEFAULT_IDLE_TIMEOUT_MS, PiiPolicyEngine.shared());
    }

    /**
     * 创建使用请求级闲置预算的事件总线。
     *
     * <p>长问题的路由分析可能超过默认 60 秒。调用方应让该预算覆盖完整的
     * 路由等待时间，并额外预留至少一个心跳周期，避免业务仍在执行时关闭连接。</p>
     */
    public SseEventBus(HttpServletResponse response, String requestId, RedisZSetCache redisCache,
                       long idleTimeoutMs) {
        this(response, requestId, redisCache, idleTimeoutMs, PiiPolicyEngine.shared());
    }

    public SseEventBus(HttpServletResponse response, String requestId, RedisZSetCache redisCache,
                       long idleTimeoutMs, PiiPolicyEngine piiPolicyEngine) {
        this.response = response;
        this.redisKey = requestId != null ? SSE_BUFFER_PREFIX + requestId : null;
        this.redisCache = redisCache;
        this.idleTimeoutMs = idleTimeoutMs > 0 ? idleTimeoutMs : DEFAULT_IDLE_TIMEOUT_MS;
        this.piiPolicyEngine = piiPolicyEngine != null ? piiPolicyEngine : PiiPolicyEngine.shared();
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
     * 每 15s 发送一次 comment 行保持连接，超过请求级闲置预算无业务事件则自动关闭。
     */
    private void startHeartbeat() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (closed) {
                    close();
                    return;
                }
                long idle = System.currentTimeMillis() - lastActivityTime.get();
                if (idle > idleTimeoutMs) {
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
        // SSE 响应在首次 flush 后必然处于 committed 状态，但输出流仍应持续写入。
        // 不能把 isCommitted() 当成连接已关闭，否则所有事件都会被静默丢弃。
        if (closed) return;
        try {
            String idLine = "id: " + seqNo + "\n";
            response.getOutputStream().write(idLine.getBytes(StandardCharsets.UTF_8));
            String rendered = piiPolicyEngine.mask(event.render());
            response.getOutputStream().write(rendered.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();

            cacheEvent(rendered);
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
    public void sendRouted(String agent, double confidence, String executionMode,
                           java.util.List<String> participatingAgents, String workflowStatus) {
        send(SseEvent.routed(agent, confidence, executionMode,
                participatingAgents, workflowStatus));
    }

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
    public boolean forwardStream(HttpURLConnection connection) {
        return forwardStream(connection, false).success();
    }

    /**
     * Forwards an upstream stream while retaining ownership of terminal
     * events. Upstream {@code token_usage} and {@code done} events are
     * consumed so the caller can emit one combined usage event followed by
     * exactly one terminal event.
     */
    public ForwardResult forwardStreamCapturingUsage(HttpURLConnection connection) {
        return forwardStream(connection, true);
    }

    private ForwardResult forwardStream(HttpURLConnection connection, boolean captureTerminalEvents) {
        TokenUsageAccumulator usage = new TokenUsageAccumulator();
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
                        forwardEvent(currentEvent, captureTerminalEvents, usage);
                    }
                    currentEvent = new StringBuilder();
                    hasData = false;
                } else {
                    currentEvent.append(line).append("\n");
                    if (line.startsWith("data:")) hasData = true;
                }
            }
            if (hasData && currentEvent.length() > 0) {
                forwardEvent(currentEvent, captureTerminalEvents, usage);
            }
            return usage.result(true);
        } catch (Exception e) {
            log.warn("[SseEventBus] 代理流异常: {}", e.getMessage());
            return usage.result(false);
        }
    }

    // ==================== 缓存管理 ====================

    private void forwardEvent(StringBuilder rawEvent, boolean captureTerminalEvents,
                              TokenUsageAccumulator usage) {
        SseEvent event = SseEvent.create();
        String data = null;
        String eventType = null;
        for (String line : rawEvent.toString().split("\n")) {
            if (line.startsWith("data:")) data = line.substring(5).trim();
            else if (line.startsWith("event:")) eventType = line.substring(6).trim();
        }
        if (eventType == null && data != null) {
            Matcher matcher = JSON_EVENT_TYPE.matcher(data);
            if (matcher.find()) eventType = matcher.group(1);
        }
        if (captureTerminalEvents && "token_usage".equals(eventType)) {
            usage.add(data);
            return;
        }
        if (captureTerminalEvents && "done".equals(eventType)) {
            return;
        }
        if ("error".equals(eventType) || "timeout".equals(eventType)) {
            usage.markUpstreamFailure();
        }
        if (eventType != null) event.event(eventType);
        if (data != null) event.data(data);
        send(event);
    }

    /** Result of forwarding a stream while capturing its usage metadata. */
    public record ForwardResult(boolean success, Long promptTokens,
                                Long completionTokens, Long totalTokens) {
        public boolean tracked() {
            return totalTokens != null;
        }
    }

    private static final class TokenUsageAccumulator {
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        private boolean initialized;
        private boolean upstreamFailed;

        void add(String json) {
            if (json == null || json.isBlank()) return;
            Long prompt = jsonLong(json, "promptTokens", "prompt_tokens", "inputTokens", "input_tokens");
            Long completion = jsonLong(json, "completionTokens", "completion_tokens",
                    "outputTokens", "output_tokens");
            Long total = jsonLong(json, "totalTokens", "total_tokens");
            if (total == null && prompt != null && completion != null) {
                total = addSaturated(prompt, completion);
            }
            if (total == null) return;
            if (!initialized) {
                promptTokens = prompt;
                completionTokens = completion;
                totalTokens = total;
                initialized = true;
                return;
            }
            promptTokens = addComplete(promptTokens, prompt);
            completionTokens = addComplete(completionTokens, completion);
            totalTokens = addSaturated(totalTokens, total);
        }

        ForwardResult result(boolean success) {
            return new ForwardResult(success && !upstreamFailed,
                    promptTokens, completionTokens, totalTokens);
        }

        void markUpstreamFailure() {
            upstreamFailed = true;
        }

        private static Long jsonLong(String json, String... names) {
            for (String name : names) {
                Pattern field = Pattern.compile("\\\"" + Pattern.quote(name)
                        + "\\\"\\s*:\\s*(\\d+)");
                Matcher matcher = field.matcher(json);
                if (!matcher.find()) continue;
                try {
                    return Long.parseLong(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        private static Long addComplete(Long left, Long right) {
            return left != null && right != null ? addSaturated(left, right) : null;
        }

        private static long addSaturated(long left, long right) {
            return right > 0 && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
        }
    }

    private void cacheEvent(String renderedEvent) {
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
            String data = extractData(renderedEvent);
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
