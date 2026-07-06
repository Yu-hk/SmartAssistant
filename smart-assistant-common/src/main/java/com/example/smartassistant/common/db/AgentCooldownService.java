/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Agent 冷却窗口管理——自动摘除故障 Agent。
 *
 * <p>参考 LiteLLM 的冷却机制设计：
 * <ul>
 *   <li>Agent 连续失败 N 次 → 进入冷却窗口（临时摘除路由）</li>
 *   <li>冷却期间路由跳过该 Agent</li>
 *   <li>冷却结束后自动恢复参与路由</li>
 * </ul>
 */
public class AgentCooldownService {

    private static final Logger log = LoggerFactory.getLogger(AgentCooldownService.class);

    private static final String COOLDOWN_KEY = "a2a:cooldown:agent:";
    private static final String FAILURE_COUNT_KEY = "a2a:cooldown:failures:";

    /** 触发冷却的连续失败次数阈值 */
    private final int failureThreshold;
    /** 冷却窗口时长（分钟） */
    private final int cooldownMinutes;

    public AgentCooldownService(StringRedisTemplate redisTemplate,
                                 int failureThreshold, int cooldownMinutes) {
        this.redisTemplate = redisTemplate;
        this.failureThreshold = failureThreshold;
        this.cooldownMinutes = cooldownMinutes;
    }

    public AgentCooldownService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, 3, 5); // 默认 3 次失败，冷却 5 分钟
    }

    private final StringRedisTemplate redisTemplate;

    /**
     * 记录一次 Agent 调用失败。
     * 达到阈值时自动进入冷却。
     */
    public void recordFailure(String agentName) {
        String key = FAILURE_COUNT_KEY + agentName;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) count = 1L;

        // 设置 TTL 防止历史失败永远累积
        if (count == 1) {
            redisTemplate.expire(key, 30, TimeUnit.MINUTES);
        }

        log.debug("[Cooldown] Agent 失败计数: agent={}, failures={}/{}",
                agentName, count, failureThreshold);

        if (count >= failureThreshold) {
            enterCooldown(agentName);
        }
    }

    /**
     * 记录一次 Agent 调用成功（重置失败计数）。
     */
    public void recordSuccess(String agentName) {
        redisTemplate.delete(FAILURE_COUNT_KEY + agentName);
        log.debug("[Cooldown] Agent 成功，失败计数已重置: agent={}", agentName);
    }

    /**
     * 检查 Agent 是否处于冷却中。
     */
    public boolean isInCooldown(String agentName) {
        Boolean inCooldown = redisTemplate.hasKey(COOLDOWN_KEY + agentName);
        return Boolean.TRUE.equals(inCooldown);
    }

    /**
     * 获取所有处于冷却中的 Agent。
     */
    public Set<String> getCoolingAgents() {
        // 注：实际应用中可以用 SCAN 或维护一个冷却集合
        return Set.of(); // 简化实现，各调用点直接检查单个 key
    }

    /**
     * 强制冷却+摘除，不等待累积失败。
     */
    public void forceCooldown(String agentName, int minutes) {
        enterCooldown(agentName, minutes);
    }

    /**
     * 手动恢复 Agent（取消冷却）。
     */
    public void recover(String agentName) {
        redisTemplate.delete(COOLDOWN_KEY + agentName);
        redisTemplate.delete(FAILURE_COUNT_KEY + agentName);
        log.info("[Cooldown] Agent 手动恢复: agent={}", agentName);
    }

    private void enterCooldown(String agentName) {
        enterCooldown(agentName, cooldownMinutes);
    }

    private void enterCooldown(String agentName, int minutes) {
        String key = COOLDOWN_KEY + agentName;
        redisTemplate.opsForValue().set(key, "cooling", minutes, TimeUnit.MINUTES);
        // 冷却开始后重置失败计数（为下一轮冷却重新累积）
        redisTemplate.delete(FAILURE_COUNT_KEY + agentName);
        log.warn("[Cooldown] Agent 已进入冷却: agent={}, duration={}分钟", agentName, minutes);
    }
}
