/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.gateway.ControlPlaneEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ⭐ 异常率阈值降级服务 — 解决"异常处理本身会制造异常"的反常识问题。
 * <p>
 * 基于 Redis Sorted Set 实现的滑动窗口错误率计数器。
 * 当 Agent 调用错误率超过阈值时触发降级模式，切断负反馈循环。
 * </p>
 *
 * <h3>降级策略</h3>
 * <ol>
 *   <li><b>轻度降级（20% ~ 40%）</b>：跳过复杂 DAG 编排，降级为单 Agent 调用</li>
 *   <li><b>重度降级（> 40%）</b>：跳过所有 Agent 调用，直接回退到内联 General 回复</li>
 * </ol>
 *
 * <h3>工作原理</h3>
 * <ul>
 *   <li>每次 Agent 调用成功/失败时调用 {@link #recordCall(boolean)}</li>
 *   <li>使用 Redis Sorted Set 存储滑动窗口内的调用记录，按时间戳排序</li>
 *   <li>{@link #getDegradationLevel()} 返回当前降级级别</li>
 *   <li>RouterService 根据降级级别调整路由策略</li>
 * </ul>
 */
@Service
public class DegradationService {

    private static final Logger log = LoggerFactory.getLogger(DegradationService.class);

    /** Redis 滑动窗口键前缀 */
    private static final String SLIDING_WINDOW_KEY = "a2a:degradation:sliding-window";

    /** 滑动窗口大小（秒）— 最近 60 秒的调用数据 */
    private static final long WINDOW_SIZE_SECONDS = 60;

    /** 轻度降级阈值：错误率超过此值触发轻度降级 */
    private static final double LIGHT_DEGRADATION_THRESHOLD = 0.20;

    /** 重度降级阈值 */
    private static final double HEAVY_DEGRADATION_THRESHOLD = 0.40;

    /** 最小调用次数，低于此值不触发降级（避免冷启动误判） */
    private static final int MIN_CALLS_FOR_DEGRADATION = 10;

    /** 当前降级级别（in-memory 快速路径，每 10 秒刷新一次） */
    private volatile DegradationLevel currentLevel = DegradationLevel.NORMAL;

    /** 上次刷新时间戳 */
    private final AtomicInteger counterSinceLastRefresh = new AtomicInteger(0);

    private final StringRedisTemplate redisTemplate;

    /** ⭐ 控制平面事件总线（可选，用于发布降级通知，与业务消息隔离） */
    @Autowired(required = false)
    private ControlPlaneEventBus controlPlaneEventBus;

    public DegradationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 记录一次 Agent 调用结果。
     *
     * @param success true = 调用成功，false = 调用失败
     */
    public void recordCall(boolean success) {
        long now = System.currentTimeMillis();
        double score = now;

        if (redisTemplate != null) {
            try {
                String key = SLIDING_WINDOW_KEY;
                // 成功=0，失败=1
                String member = now + ":" + (success ? "0" : "1") + ":" + Thread.currentThread().threadId();

                redisTemplate.opsForZSet().add(key, member, score);
                redisTemplate.expire(key, Duration.ofSeconds(WINDOW_SIZE_SECONDS * 2));

                // 清理过期数据
                long cutoff = now - WINDOW_SIZE_SECONDS * 1000;
                redisTemplate.opsForZSet().removeRangeByScore(key, 0, cutoff);

                // 每 10 次调用刷新一次降级级别（批次更新，避免 Redis 压力）
                if (counterSinceLastRefresh.incrementAndGet() >= 10) {
                    counterSinceLastRefresh.set(0);
                    refreshDegradationLevel();
                }
            } catch (Exception e) {
                log.warn("[Degradation] 记录调用失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 获取当前降级级别。
     * <p>
     * 配合 {@link #recordCall(boolean)} 使用。
     * 降级级别使用本地缓存，每 10 次调用或显式调用 {@link #refreshDegradationLevel()} 更新。
     * </p>
     *
     * @return 当前降级级别
     */
    public DegradationLevel getDegradationLevel() {
        return currentLevel;
    }

    /**
     * 强制刷新降级级别（从 Redis 重新计算）。
     */
    public void refreshDegradationLevel() {
        if (redisTemplate == null) {
            currentLevel = DegradationLevel.NORMAL;
            return;
        }

        try {
            long now = System.currentTimeMillis();
            long cutoff = now - WINDOW_SIZE_SECONDS * 1000;
            String key = SLIDING_WINDOW_KEY;

            // 统计窗口内总调用次数
            Long totalCalls = redisTemplate.opsForZSet().count(key, cutoff, now);
            if (totalCalls == null || totalCalls < MIN_CALLS_FOR_DEGRADATION) {
                currentLevel = DegradationLevel.NORMAL;
                return;
            }

            // 统计失败次数（member 包含 ":1" 后缀）
            Set<String> failedMembers = redisTemplate.opsForZSet().rangeByScore(key, cutoff, now);
            if (failedMembers == null || failedMembers.isEmpty()) {
                currentLevel = DegradationLevel.NORMAL;
                return;
            }

            long failedCalls = failedMembers.stream()
                    .filter(m -> m != null && m.endsWith(":1"))
                    .count();

            double errorRate = (double) failedCalls / totalCalls;

            DegradationLevel newLevel;
            if (errorRate >= HEAVY_DEGRADATION_THRESHOLD) {
                newLevel = DegradationLevel.HEAVY;
            } else if (errorRate >= LIGHT_DEGRADATION_THRESHOLD) {
                newLevel = DegradationLevel.LIGHT;
            } else {
                newLevel = DegradationLevel.NORMAL;
            }

            if (newLevel != currentLevel) {
                log.warn("[Degradation] 🔄 降级级别变更: {} → {} (errorRate={:.2f}, calls={})",
                        currentLevel, newLevel, errorRate, totalCalls);

                // ⭐ 通过控制平面事件总线发布降级通知（与业务消息隔离）
                if (controlPlaneEventBus != null) {
                    controlPlaneEventBus.publish("router", "DEGRADATION_LEVEL_CHANGE",
                            Map.of(
                                    "from", currentLevel.name(),
                                    "to", newLevel.name(),
                                    "errorRate", String.format("%.2f", errorRate),
                                    "totalCalls", totalCalls,
                                    "timestamp", System.currentTimeMillis()
                            ));
                }
            }

            currentLevel = newLevel;

        } catch (Exception e) {
            log.warn("[Degradation] 刷新降级级别失败: {}", e.getMessage());
            // 刷新失败时保持当前级别，不做变更
        }
    }

    /**
     * 当前是否处于降级模式（非 NORMAL）。
     */
    public boolean isDegraded() {
        return currentLevel != DegradationLevel.NORMAL;
    }

    /**
     * 降级级别枚举。
     */
    public enum DegradationLevel {
        /** 正常模式，不做限制 */
        NORMAL,
        /** 轻度降级：跳过复杂 DAG，降级为单 Agent 调用 */
        LIGHT,
        /** 重度降级：跳过所有 Agent 调用，回退到内联 General 回复 */
        HEAVY
    }
}
