/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

/**
 * SSE 连接管理器——限制单用户最大并发 SSE 连接数。
 *
 * <p>参考面试题 #5 的单用户限流设计：
 * <ul>
 *   <li>内存计数 + Redis 计数双校验</li>
 *   <li>超限时返回客户端「忙」信号，不阻塞新连接</li>
 *   <li>连接关闭时自动释放计数</li>
 * </ul>
 */
public class SseConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(SseConnectionManager.class);

    /** Redis key 前缀 */
    private static final String USER_CONN_KEY = "a2a:sse:conn:user:";

    /** 单用户最大并发 SSE 数 */
    private final int maxPerUser;
    /** 连接 TTL（秒）—— 防 Redis 残留 */
    private static final long CONN_TTL_SECONDS = 300;

    private final StringRedisTemplate redisTemplate;

    /** 内存中活跃连接计数（快速路径，不依赖 Redis） */
    private final ConcurrentHashMap<String, AtomicInteger> localCounts = new ConcurrentHashMap<>();

    public SseConnectionManager(StringRedisTemplate redisTemplate, int maxPerUser) {
        this.redisTemplate = redisTemplate;
        this.maxPerUser = maxPerUser;
    }

    public SseConnectionManager(StringRedisTemplate redisTemplate) {
        this(redisTemplate, 3); // 默认每用户 3 个并发
    }

    /**
     * 尝试注册一个新连接。
     *
     * @param userId 用户标识
     * @param requestId 请求 ID
     * @return true = 允许建立连接，false = 超过限额
     */
    public boolean tryRegister(String userId, String requestId) {
        // 1. 内存快速检查
        AtomicInteger counter = localCounts.computeIfAbsent(userId, k -> new AtomicInteger(0));
        int current = counter.incrementAndGet();
        if (current > maxPerUser) {
            counter.decrementAndGet();
            log.warn("[SseConnManager] 用户连接超限: userId={}, current={}, max={}",
                    userId, current - 1, maxPerUser);
            return false;
        }

        // 2. Redis 双校验
        String redisKey = USER_CONN_KEY + userId;
        try {
            Long redisCount = redisTemplate.opsForSet().size(redisKey);
            if (redisCount != null && redisCount >= maxPerUser) {
                counter.decrementAndGet();
                log.warn("[SseConnManager] Redis 连接超限: userId={}, count={}", userId, redisCount);
                return false;
            }
            redisTemplate.opsForSet().add(redisKey, requestId);
            redisTemplate.expire(redisKey, CONN_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[SseConnManager] Redis 校验异常: {}", e.getMessage());
            // Redis 不可用时降级为仅内存检查
        }

        log.debug("[SseConnManager] 连接注册成功: userId={}, requestId={}", userId, requestId);
        return true;
    }

    /**
     * 释放连接（连接关闭时调用）。
     */
    public void release(String userId, String requestId) {
        // 1. 内存减计数
        AtomicInteger counter = localCounts.get(userId);
        if (counter != null) {
            int remaining = counter.decrementAndGet();
            if (remaining <= 0) {
                localCounts.remove(userId);
            }
        }

        // 2. Redis 清理
        String redisKey = USER_CONN_KEY + userId;
        try {
            redisTemplate.opsForSet().remove(redisKey, requestId);
        } catch (Exception e) {
            log.debug("[SseConnManager] Redis 释放异常: {}", e.getMessage());
        }
    }
}
