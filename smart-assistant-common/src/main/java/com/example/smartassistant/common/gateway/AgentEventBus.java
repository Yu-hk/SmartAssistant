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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * ⭐ 基于 Redis List 的异步 Agent 事件总线。
 * <p>
 * 每个 Agent 对应一个 Redis List key {@code agent:events:{agentName}}。
 * Router 通过 {@link #publishEvent(String, String, Object)} RPUSH 发布事件，
 * Agent 通过 {@link #startConsumer(String, Consumer)} 启动虚拟线程 BLPOP 异步消费。
 * </p>
 *
 * <p>与同步 HTTP Handoff 相比：</p>
 * <ul>
 *   <li>解耦：发布者不等待消费者</li>
 *   <li>弹性：消费者处理慢时事件堆积，不阻塞上游</li>
 *   <li>持久化：Redis List 可持久化，服务重启不丢</li>
 *   <li>重试：消费失败自动重新 RPUSH</li>
 * </ul>
 */
public class AgentEventBus {

    private static final Logger log = LoggerFactory.getLogger(AgentEventBus.class);

    /** Redis List 键前缀 */
    private static final String EVENT_KEY_PREFIX = "agent:events:";

    /** BLPOP 轮询超时（秒） */
    private static final long BLPOP_TIMEOUT_SECONDS = 5;

    /** 已启动的消费者线程标记 */
    private final ConcurrentHashMap<String, Boolean> consumerStarted = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AgentEventBus(StringRedisTemplate redisTemplate) {
        this(redisTemplate, new ObjectMapper());
    }

    public AgentEventBus(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * 向指定 Agent 发布事件（RPUSH 到 Redis List）。
     *
     * @param targetAgent 目标 Agent 名称
     * @param eventType   事件类型（如 "HANDOFF"、"TASK"）
     * @param payload     事件负载（JSON 序列化）
     */
    public void publishEvent(String targetAgent, String eventType, Object payload) {
        String key = EVENT_KEY_PREFIX + targetAgent;
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", eventType);
            event.put("payload", payload);
            event.put("timestamp", System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().rightPush(key, json);
            log.debug("[AgentEventBus] 发布事件: agent={}, type={}", targetAgent, eventType);
        } catch (JsonProcessingException e) {
            log.warn("[AgentEventBus] 事件序列化失败: agent={}, error={}", targetAgent, e.getMessage());
        }
    }

    /**
     * 启动指定 Agent 的事件消费者（虚拟线程 + BLPOP）。
     * <p>
     * 每个 Agent 最多启动一个消费者线程，重复调用忽略。
     * 消费失败时事件自动重新入队（尾部）。
     * </p>
     */
    public void startConsumer(String agentName, Consumer<String> handler) {
        if (consumerStarted.putIfAbsent(agentName, Boolean.TRUE) != null) {
            log.debug("[AgentEventBus] 消费者已存在: agent={}", agentName);
            return;
        }

        String key = EVENT_KEY_PREFIX + agentName;
        Thread.startVirtualThread(() -> {
            log.info("[AgentEventBus] 🚀 启动消费者: agent={}, key={}", agentName, key);
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        String eventJson = redisTemplate.opsForList().leftPop(
                                key, BLPOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (eventJson == null) continue;

                        log.debug("[AgentEventBus] 消费事件: agent={}, len={}", agentName, eventJson.length());
                        try {
                            handler.accept(eventJson);
                        } catch (Exception e) {
                            log.warn("[AgentEventBus] 事件处理异常, 重新入队: agent={}", agentName);
                            redisTemplate.opsForList().rightPush(key, eventJson);
                        }
                    } catch (Exception e) {
                        if (Thread.currentThread().isInterrupted()) break;
                        log.warn("[AgentEventBus] 消费异常: agent={}", agentName);
                    }
                }
            } finally {
                consumerStarted.remove(agentName);
                log.info("[AgentEventBus] 消费者停止: agent={}", agentName);
            }
        });
    }

    /** 停止消费者 */
    public void stopConsumer(String agentName) {
        consumerStarted.remove(agentName);
    }

    /** 获取队列大小 */
    public long getQueueSize(String agentName) {
        Long size = redisTemplate.opsForList().size(EVENT_KEY_PREFIX + agentName);
        return size != null ? size : 0;
    }
}
