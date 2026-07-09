/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.idempotent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis SETNX 的通用分布式锁。
 *
 * <p>用于 Agent 写入操作的幂等保护，防止多实例并发执行同一任务。</p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 *   DistributedLock lock = lockService.getLock("order:create:ORD-001");
 *   if (lock.tryLock(5, TimeUnit.SECONDS)) {
 *       try {
 *           // 执行写操作
 *       } finally {
 *           lock.unlock();
 *       }
 *   }
 * }</pre>
 */
@Component
public class DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);

    private static final String LOCK_KEY_PREFIX = "a2a:lock:";
    private static final long DEFAULT_LOCK_TTL_MS = 10_000;   // 默认锁超时 10s
    private static final long DEFAULT_WAIT_MS = 3_000;        // 默认等待 3s
    private static final long RETRY_INTERVAL_MS = 100;        // 轮询间隔 100ms

    private final StringRedisTemplate redisTemplate;

    public DistributedLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 获取一把锁（自动带上前缀）。
     *
     * @param lockKey 锁标识（如 "order:create:ORD-001"）
     * @return 锁实例
     */
    public LockInstance getLock(String lockKey) {
        return new LockInstance(LOCK_KEY_PREFIX + lockKey, DEFAULT_LOCK_TTL_MS);
    }

    public LockInstance getLock(String lockKey, long lockTtlMs) {
        return new LockInstance(LOCK_KEY_PREFIX + lockKey, lockTtlMs);
    }

    /**
     * 获取一把锁并设置等待时间。
     */
    public LockInstance getLock(String lockKey, long lockTtlMs, long waitMs) {
        return new LockInstance(LOCK_KEY_PREFIX + lockKey, lockTtlMs, waitMs);
    }

    /**
     * 锁实例。
     */
    public class LockInstance {
        private final String redisKey;
        private final long ttlMs;
        private final long waitMs;
        private final String value;   // 解锁时需校验归属

        LockInstance(String redisKey, long ttlMs) {
            this(redisKey, ttlMs, DEFAULT_WAIT_MS);
        }

        LockInstance(String redisKey, long ttlMs, long waitMs) {
            this.redisKey = redisKey;
            this.ttlMs = ttlMs;
            this.waitMs = waitMs;
            this.value = Thread.currentThread().getName() + "@" + System.nanoTime();
        }

        /**
         * 尝试获取锁，等待 waitMs 毫秒。
         *
         * @return true 获取成功
         */
        public boolean tryLock() {
            return tryLock(waitMs, TimeUnit.MILLISECONDS);
        }

        public boolean tryLock(long timeout, TimeUnit unit) {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            while (System.currentTimeMillis() < deadline) {
                Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(redisKey, value, ttlMs, TimeUnit.MILLISECONDS);
                if (Boolean.TRUE.equals(acquired)) {
                    log.debug("[DistributedLock] 获取锁成功: key={}, value={}, ttl={}ms",
                            redisKey, value, ttlMs);
                    return true;
                }
                // 等待重试
                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            log.warn("[DistributedLock] 获取锁超时: key={}, waited={}ms", redisKey, unit.toMillis(timeout));
            return false;
        }

        /**
         * 释放锁（使用 Redis Lua 脚本保证原子性：只有持有者才能释放）。
         */
        public void unlock() {
            try {
                // 使用 RedisScript 执行原子性解锁
                String luaScript = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('del', KEYS[1]) else return 0 end";
                org.springframework.data.redis.core.script.DefaultRedisScript<Long> script =
                        new org.springframework.data.redis.core.script.DefaultRedisScript<>();
                script.setScriptText(luaScript);
                script.setResultType(Long.class);
                Long result = redisTemplate.execute(script,
                        java.util.Collections.singletonList(redisKey), value);
                if (result != null && result > 0) {
                    log.debug("[DistributedLock] 释放锁成功: key={}", redisKey);
                } else {
                    log.warn("[DistributedLock] 锁已过期或非持有者释放: key={}", redisKey);
                }
            } catch (Exception e) {
                log.warn("[DistributedLock] 释放锁异常: key={}, error={}", redisKey, e.getMessage());
            }
        }
    }
}
