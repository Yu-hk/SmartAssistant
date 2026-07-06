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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 多级 Token 预算管理——per-user / per-app 三级预算控制。
 *
 * <p>参考文章三级预算设计：
 * <ul>
 *   <li>L1: 全局每日预算（整个系统）</li>
 *   <li>L2: per-app 每日预算（每个业务线/模块）</li>
 *   <li>L3: per-user 每日预算（每个用户）</li>
 * </ul>
 * 超限不硬拒绝，而是软降级到便宜模型。
 */
public class TokenBudgetService {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetService.class);

    /** Redis key 前缀 */
    private static final String GLOBAL_KEY = "a2a:budget:global:";
    private static final String APP_KEY = "a2a:budget:app:";
    private static final String USER_KEY = "a2a:budget:user:";

    /** 预算限额配置（Token 数） */
    private final long globalDailyLimit;
    private final long appDailyLimit;
    private final long userDailyLimit;

    /** 降级模型名称（超限后替换） */
    private final String downgradeModel;

    private final StringRedisTemplate redisTemplate;

    public TokenBudgetService(StringRedisTemplate redisTemplate,
                               long globalDailyLimit, long appDailyLimit,
                               long userDailyLimit, String downgradeModel) {
        this.redisTemplate = redisTemplate;
        this.globalDailyLimit = globalDailyLimit;
        this.appDailyLimit = appDailyLimit;
        this.userDailyLimit = userDailyLimit;
        this.downgradeModel = downgradeModel;
    }

    /**
     * 检查当前请求是否在预算内。
     *
     * @param appId 应用/模块 ID
     * @param userId 用户 ID
     * @param estimatedTokens 本次请求预估 Token 数
     * @return BudgetResult — ALLOW 或 DOWNGRADE
     */
    public BudgetResult check(String appId, String userId, long estimatedTokens) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        // L1: 全局预算
        long globalSpent = getDailySpent(GLOBAL_KEY + today);
        if (globalSpent + estimatedTokens > globalDailyLimit) {
            log.warn("[Budget] 全局预算超限: spent={}, limit={}", globalSpent, globalDailyLimit);
            return BudgetResult.downgrade("全局预算超限");
        }

        // L2: App 预算
        long appSpent = getDailySpent(APP_KEY + appId + ":" + today);
        if (appSpent + estimatedTokens > appDailyLimit) {
            log.warn("[Budget] App 预算超限: app={}, spent={}, limit={}",
                    appId, appSpent, appDailyLimit);
            return BudgetResult.downgrade("应用预算超限");
        }

        // L3: 用户预算
        if (userId != null) {
            long userSpent = getDailySpent(USER_KEY + userId + ":" + today);
            if (userSpent + estimatedTokens > userDailyLimit) {
                log.warn("[Budget] 用户预算超限: userId={}, spent={}, limit={}",
                        userId, userSpent, userDailyLimit);
                return BudgetResult.downgrade("用户预算超限");
            }
        }

        return BudgetResult.allow();
    }

    /**
     * 记录本次请求的 Token 消耗。
     */
    public void record(String appId, String userId, long tokens) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        long ttl = TimeUnit.DAYS.toSeconds(2); // 保留 2 天防跨日查询

        incrementAndExpire(GLOBAL_KEY + today, tokens, ttl);
        incrementAndExpire(APP_KEY + appId + ":" + today, tokens, ttl);
        if (userId != null) {
            incrementAndExpire(USER_KEY + userId + ":" + today, tokens, ttl);
        }
    }

    /**
     * 获取各层级今日预算消耗。
     */
    public BudgetUsage getUsage(String appId, String userId) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return new BudgetUsage(
                getDailySpent(GLOBAL_KEY + today), globalDailyLimit,
                getDailySpent(APP_KEY + appId + ":" + today), appDailyLimit,
                userId != null ? getDailySpent(USER_KEY + userId + ":" + today) : 0,
                userId != null ? userDailyLimit : 0
        );
    }

    private long getDailySpent(String key) {
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Long.parseLong(val) : 0L;
    }

    private void incrementAndExpire(String key, long amount, long ttl) {
        redisTemplate.opsForValue().increment(key, amount);
        redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
    }

    // ==================== 结果类 ====================

    public record BudgetResult(boolean allowed, String reason, String downgradeModel) {
        public static BudgetResult allow() {
            return new BudgetResult(true, null, null);
        }
        public static BudgetResult downgrade(String reason) {
            return new BudgetResult(false, reason, null);
        }
        public BudgetResult withDowngradeModel(String model) {
            return new BudgetResult(false, reason, model);
        }
    }

    public record BudgetUsage(
            long globalSpent, long globalLimit,
            long appSpent, long appLimit,
            long userSpent, long userLimit
    ) {
        public double globalPercent() { return percent(globalSpent, globalLimit); }
        public double appPercent() { return percent(appSpent, appLimit); }
        public double userPercent() { return percent(userSpent, userLimit); }
        private static double percent(long spent, long limit) {
            return limit > 0 ? Math.round(1000.0 * spent / limit) / 10.0 : 0;
        }
    }
}
