/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理服务
 * 为每个用户管理唯一的 threadId，支持会话隔离和清理。
 * <p>
 * <b>Redis 改造</b>：使用 Redis Hash 替代本地 ConcurrentHashMap，支持多实例部署。
 * Key 分布：
 * <ul>
 *   <li>{@code session:user:{userId}} → threadId（String）</li>
 *   <li>{@code session:access:{threadId}} → 最后访问时间戳（毫秒）</li>
 * </ul>
 * </p>
 */
@Service
public class SessionManagementService {

    private static final Logger log = LoggerFactory.getLogger(SessionManagementService.class);

    private static final String USER_SESSION_PREFIX = "session:user:";
    private static final String SESSION_ACCESS_PREFIX = "session:access:";

    private final StringRedisTemplate redisTemplate;

    // ⭐ 会话超时时间（毫秒）：从配置文件读取，默认 30 分钟
    @Value("${session.timeout-ms:1800000}")
    private long sessionTimeoutMs;

    public SessionManagementService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 获取或创建用户的 threadId
     * 如果用户已有活跃会话，返回现有 threadId
     * 如果会话已过期或不存在，创建新的 threadId
     *
     * @param userId 用户 ID（可以从请求头、Cookie 或 JWT 中获取）
     * @return 唯一的 threadId
     */
    public String getOrCreateThreadId(String userId) {
        if (userId == null || userId.isEmpty()) {
            // 匿名用户：每次生成新的 threadId
            String newThreadId = generateNewThreadId();
            log.debug("[Session] 匿名用户，生成新 threadId: {}", newThreadId);
            return newThreadId;
        }

        String userSessionKey = USER_SESSION_PREFIX + userId;
        String existingThreadId = redisTemplate.opsForValue().get(userSessionKey);

        // 检查现有会话是否有效
        if (existingThreadId != null && isSessionValid(existingThreadId)) {
            // 更新最后访问时间
            String accessKey = SESSION_ACCESS_PREFIX + existingThreadId;
            redisTemplate.opsForValue().set(accessKey, String.valueOf(System.currentTimeMillis()));
            log.debug("[Session] 复用现有会话: userId={}, threadId={}", userId, existingThreadId);
            return existingThreadId;
        }

        // 创建新会话
        String newThreadId = generateNewThreadId();
        redisTemplate.opsForValue().set(userSessionKey, newThreadId);
        String accessKey = SESSION_ACCESS_PREFIX + newThreadId;
        redisTemplate.opsForValue().set(accessKey, String.valueOf(System.currentTimeMillis()));

        // 清理旧会话
        if (existingThreadId != null) {
            redisTemplate.delete(SESSION_ACCESS_PREFIX + existingThreadId);
            log.info("[Session] 用户会话已更新: userId={}, oldThreadId={}, newThreadId={}",
                    userId, existingThreadId, newThreadId);
        } else {
            log.info("[Session] 创建新会话: userId={}, threadId={}", userId, newThreadId);
        }

        return newThreadId;
    }

    /**
     * 强制刷新用户会话（生成新的 threadId）
     * 适用于用户明确要求"重新开始"或切换场景
     *
     * @param userId 用户 ID
     * @return 新的 threadId
     */
    public String refreshSession(String userId) {
        if (userId == null || userId.isEmpty()) {
            return generateNewThreadId();
        }

        String userSessionKey = USER_SESSION_PREFIX + userId;
        String oldThreadId = redisTemplate.opsForValue().getAndDelete(userSessionKey);

        if (oldThreadId != null) {
            redisTemplate.delete(SESSION_ACCESS_PREFIX + oldThreadId);
            log.info("[Session] 强制刷新会话: userId={}, oldThreadId={}", userId, oldThreadId);
        }

        String newThreadId = generateNewThreadId();
        redisTemplate.opsForValue().set(userSessionKey, newThreadId);
        String accessKey = SESSION_ACCESS_PREFIX + newThreadId;
        redisTemplate.opsForValue().set(accessKey, String.valueOf(System.currentTimeMillis()));

        return newThreadId;
    }

    /**
     * 清理过期会话（自动定时调度，每 60 秒执行一次）
     * 使用 Redis SCAN 遍历 session:access:* 键，避免 KEYS 阻塞
     */
    @Scheduled(fixedDelay = 60000)
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        int cleanedCount = 0;

        Set<String> accessKeys = redisTemplate.keys(SESSION_ACCESS_PREFIX + "*");
        if (accessKeys == null || accessKeys.isEmpty()) {
            return;
        }

        for (String accessKey : accessKeys) {
            String timestampStr = redisTemplate.opsForValue().get(accessKey);
            if (timestampStr == null) continue;

            long lastAccess = Long.parseLong(timestampStr);
            if (now - lastAccess > sessionTimeoutMs) {
                String threadId = accessKey.substring(SESSION_ACCESS_PREFIX.length());
                // 删除访问记录
                redisTemplate.delete(accessKey);
                // 删除对应的用户会话映射
                Set<String> userKeys = redisTemplate.keys(USER_SESSION_PREFIX + "*");
                if (userKeys != null) {
                    for (String userKey : userKeys) {
                        String tid = redisTemplate.opsForValue().get(userKey);
                        if (threadId.equals(tid)) {
                            redisTemplate.delete(userKey);
                            break;
                        }
                    }
                }

                cleanedCount++;
                log.debug("[Session] 清理过期会话: threadId={}, 最后访问: {}分钟前",
                        threadId, (now - lastAccess) / 60000);
            }
        }

        if (cleanedCount > 0) {
            long remaining = redisTemplate.keys(SESSION_ACCESS_PREFIX + "*").size();
            log.info("[Session] 会话清理完成: 清理 {} 个过期会话, 剩余 {} 个活跃会话",
                    cleanedCount, remaining);
        }
    }

    /**
     * 获取当前活跃会话数
     */
    public int getActiveSessionCount() {
        Set<String> accessKeys = redisTemplate.keys(SESSION_ACCESS_PREFIX + "*");
        return accessKeys != null ? accessKeys.size() : 0;
    }

    /**
     * 检查会话是否有效（未过期）
     */
    private boolean isSessionValid(String threadId) {
        String accessKey = SESSION_ACCESS_PREFIX + threadId;
        String timestampStr = redisTemplate.opsForValue().get(accessKey);
        if (timestampStr == null) {
            return false;
        }

        long lastAccess = Long.parseLong(timestampStr);
        long now = System.currentTimeMillis();
        return (now - lastAccess) <= sessionTimeoutMs;
    }

    /**
     * 生成新的 threadId
     * 格式：session_{timestamp}_{random}
     */
    private String generateNewThreadId() {
        long timestamp = System.currentTimeMillis();
        long random = (long)(Math.random() * 10000);
        return String.format("session_%d_%d", timestamp, random);
    }
}
