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
 * 支持熔断器三态（关闭/半开/打开），半开状态下通过探测请求自动恢复。
 * </p>
 *
 * <h3>熔断三态</h3>
 * <ol>
 *   <li><b>NORMAL（关闭）</b>：正常调用，不做限制</li>
 *   <li><b>HALF_OPEN（半开）</b>：错误率已降至阈值以下，放行探测请求验证恢复</li>
 *   <li><b>LIGHT/HEAVY（打开）</b>：错误率超阈值，停止复杂 DAG 或全部 Agent 调用</li>
 * </ol>
 *
 * <h3>降级策略</h3>
 * <ol>
 *   <li><b>轻度降级（20% ~ 40%）</b>：跳过复杂 DAG 编排，降级为单 Agent 调用</li>
 *   <li><b>重度降级（> 40%）</b>：跳过所有 Agent 调用，直接回退到内联 General 回复</li>
 * </ol>
 *
 * <h3>半开恢复探测</h3>
 * <p>
 * 进入 HALF_OPEN 后，每 {@link #HALF_OPEN_PROBE_INTERVAL_MS} 毫秒放行一次探测请求。
 * 探测成功 → 回到 NORMAL；探测失败 → 回到 HEAVY。
 * 避免熔断打开后恢复时的流量冲击。
 * </p>
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

    // ═══════════════════════════════════════════════════════════
    // ⭐ 半开（HALF_OPEN）熔断恢复
    // ═══════════════════════════════════════════════════════════

    /** 半开状态探测间隔（毫秒）：放行探测请求的最小时间间隔 */
    private static final long HALF_OPEN_PROBE_INTERVAL_MS = 30_000;

    /** 上次探测请求的时间戳 */
    private long lastProbeTimestamp = 0;

    /** 当前降级级别（in-memory 快速路径，每 10 次调用刷新一次） */
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
     * <p>
     * 在 {@link #HALF_OPEN} 状态下，此方法会检查是否到达探测间隔。
     * 如果到达，允许一次探测请求通过（返回之前降级级别的上级级别）。
     * </p>
     *
     * @return 当前降级级别
     */
    public DegradationLevel getDegradationLevel() {
        // ⭐ HALF_OPEN 状态下：检查是否可以放行探测请求
        if (currentLevel == DegradationLevel.HALF_OPEN) {
            long now = System.currentTimeMillis();
            if (now - lastProbeTimestamp >= HALF_OPEN_PROBE_INTERVAL_MS) {
                return DegradationLevel.HALF_OPEN; // 调用方据此走降级路径，但实际探测在 recordCall 中处理
            }
            // 未到探测间隔 → 继续降级
            return DegradationLevel.HEAVY;
        }
        return currentLevel;
    }

    /**
     * 记录一次探测结果。由调用方（RouterService）在探测请求完成后调用。
     *
     * @param success true = 探测成功（恢复 NORMAL），false = 探测失败（回到 HEAVY）
     */
    public void recordProbeResult(boolean success) {
        this.lastProbeTimestamp = System.currentTimeMillis();
        if (success) {
            log.info("[Degradation] ✅ 半开探测成功，恢复 NORMAL 模式");
            currentLevel = DegradationLevel.NORMAL;
        } else {
            log.warn("[Degradation] ❌ 半开探测失败，回到 HEAVY 降级");
            currentLevel = DegradationLevel.HEAVY;
        }
        if (controlPlaneEventBus != null) {
            controlPlaneEventBus.publish("router", "PROBE_RESULT",
                    Map.of("success", success, "level", currentLevel.name()));
        }
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
            } else if (currentLevel == DegradationLevel.HEAVY || currentLevel == DegradationLevel.LIGHT) {
                // ⭐ 半开状态：错误率已降至阈值以下，进入 HALF_OPEN 而非直接 NORMAL
                // 需通过探测请求验证恢复后再回到 NORMAL
                newLevel = DegradationLevel.HALF_OPEN;
            } else if (currentLevel == DegradationLevel.HALF_OPEN) {
                // 已在半开状态且错误率正常 → 保持 HALF_OPEN，等待探测
                newLevel = DegradationLevel.HALF_OPEN;
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
     * 降级级别枚举（熔断三态）。
     */
    public enum DegradationLevel {
        /** 正常模式，不做限制（熔断关闭） */
        NORMAL,
        /** 半开状态：错误率已降至阈值以下，等待探测请求验证恢复（熔断半开） */
        HALF_OPEN,
        /** 轻度降级：跳过复杂 DAG，降级为单 Agent 调用（熔断打开） */
        LIGHT,
        /** 重度降级：跳过所有 Agent 调用，回退到内联 General 回复（熔断打开） */
        HEAVY
    }
}
