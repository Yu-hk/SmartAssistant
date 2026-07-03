/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * ⭐ 控制平面事件总线 — 与业务消息（{@link AgentEventBus}）分离。
 * <p>
 * 解决反常识 2"异常处理本身会制造异常"中的负反馈循环：
 * 异常处理、熔断状态变更、降级通知等控制信令走专用通道，
 * 与业务消息（Agent Handoff、任务调度）资源隔离。
 * </p>
 *
 * <h3>与 {@link AgentEventBus} 的区别</h3>
 * <table>
 *   <tr><th>维度</th><th>AgentEventBus（数据平面）</th><th>ControlPlaneEventBus（控制平面）</th></tr>
 *   <tr><td>用途</td><td>Agent Handoff、任务调度</td><td>熔断状态、降级通知、异常率告警</td></tr>
 *   <tr><td>Redis Key 前缀</td><td>{@code agent:events:}</td><td>{@code ctrl:events:}</td></tr>
 *   <tr><td>Redis 连接</td><td>主 Redis</td><td>可选独立 Redis（避免相互影响）</td></tr>
 *   <tr><td>优先级</td><td>低（可丢弃）</td><td>高（必须送达）</td></tr>
 *   <tr><td>事件量</td><td>高（数百/秒）</td><td>低（数/秒）</td></tr>
 * </table>
 */
public class ControlPlaneEventBus {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneEventBus.class);

    /** 控制平面 Redis List 键前缀 */
    private static final String CTRL_EVENT_KEY_PREFIX = "ctrl:events:";

    /** BLPOP 轮询超时（秒） */
    private static final long BLPOP_TIMEOUT_SECONDS = 5;

    /** 事件去重键前缀（SETNX），5 分钟自动过期 */
    private static final String DEDUP_KEY_PREFIX = "ctrl:events:dedup:";
    private static final long DEDUP_KEY_TTL_SECONDS = 300;

    /** 已启动的消费者线程标记 */
    private final ConcurrentHashMap<String, Boolean> consumerStarted = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ControlPlaneEventBus(StringRedisTemplate redisTemplate) {
        this(redisTemplate, new ObjectMapper());
    }

    public ControlPlaneEventBus(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * 发布控制平面事件。
     *
     * @param target   目标组件名称（如 "router"、"consumer"）
     * @param eventType 事件类型（如 "CIRCUIT_BREAKER_OPEN"、"DEGRADATION_LEVEL_CHANGE"）
     * @param payload  事件负载
     */
    public void publish(String target, String eventType, Object payload) {
        String key = CTRL_EVENT_KEY_PREFIX + target;
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventId", UUID.randomUUID().toString());
            event.put("type", eventType);
            event.put("payload", payload);
            event.put("timestamp", System.currentTimeMillis());
            event.put("source", "control-plane");
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().rightPush(key, json);
            log.debug("[ControlPlaneBus] 发布事件: target={}, type={}", target, eventType);
        } catch (JsonProcessingException e) {
            log.warn("[ControlPlaneBus] 序列化失败: target={}, error={}", target, e.getMessage());
        }
    }

    /**
     * 启动控制平面事件消费者。
     */
    public void startConsumer(String target, Consumer<String> handler) {
        if (consumerStarted.putIfAbsent(target, Boolean.TRUE) != null) {
            log.debug("[ControlPlaneBus] 消费者已存在: target={}", target);
            return;
        }

        String key = CTRL_EVENT_KEY_PREFIX + target;
        Thread.startVirtualThread(() -> {
            log.info("[ControlPlaneBus] 🚀 启动消费者: target={}", target);
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        String eventJson = redisTemplate.opsForList().leftPop(
                                key, BLPOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (eventJson == null) continue;

                        try {
                            handler.accept(eventJson);
                        } catch (Exception e) {
                            // 幂等去重防止死循环
                            String eventId = extractEventId(eventJson);
                            if (eventId != null) {
                                String dedupKey = DEDUP_KEY_PREFIX + eventId;
                                Boolean first = redisTemplate.opsForValue().setIfAbsent(
                                        dedupKey, "1", Duration.ofSeconds(DEDUP_KEY_TTL_SECONDS));
                                if (Boolean.TRUE.equals(first)) {
                                    redisTemplate.opsForList().rightPush(key, eventJson);
                                }
                            }
                        }
                    } catch (Exception e) {
                        if (Thread.currentThread().isInterrupted()) break;
                    }
                }
            } finally {
                consumerStarted.remove(target);
            }
        });
    }

    public void stopConsumer(String target) {
        consumerStarted.remove(target);
    }

    /** 从事件 JSON 中快速提取 eventId */
    private String extractEventId(String eventJson) {
        if (eventJson == null || eventJson.length() < 20) return null;
        try {
            int idIdx = eventJson.indexOf("\"eventId\":\"");
            if (idIdx < 0) return null;
            int start = idIdx + "\"eventId\":\"".length();
            int end = eventJson.indexOf('"', start);
            return end > start ? eventJson.substring(start, end) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
