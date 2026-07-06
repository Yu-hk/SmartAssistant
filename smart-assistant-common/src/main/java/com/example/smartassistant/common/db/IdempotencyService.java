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

import java.util.concurrent.TimeUnit;

/**
 * 幂等性保障服务——防止 Agent 任务/工具重复执行。
 *
 * <p>参考腾讯面试题 #7 的幂等设计：
 * <ol>
 *   <li>全局唯一 TaskID（userId + 请求特征 + 时间戳）</li>
 *   <li>Redis 分布式锁（锁过期时间 > 最大执行时长）</li>
 *   <li>任务状态持久化：待执行 / 执行中 / 成功 / 失败</li>
 * </ol>
 */
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private static final String LOCK_PREFIX = "a2a:idempotent:lock:";
    private static final String STATE_PREFIX = "a2a:idempotent:state:";
    private static final String RESULT_PREFIX = "a2a:idempotent:result:";

    /** 默认锁超时（毫秒） */
    private static final long DEFAULT_LOCK_TIMEOUT_MS = 30_000;

    public enum TaskState { PENDING, RUNNING, SUCCESS, FAILED }

    private final StringRedisTemplate redisTemplate;

    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取执行锁。
     *
     * @param taskId  全局唯一任务 ID
     * @param timeoutMs 锁超时（毫秒）
     * @return true 表示首次执行（获得锁），false 表示已存在执行中或已完成
     */
    public boolean tryLock(String taskId, long timeoutMs) {
        String lockKey = LOCK_PREFIX + taskId;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "locked", timeoutMs, TimeUnit.MILLISECONDS);
        if (Boolean.TRUE.equals(locked)) {
            setState(taskId, TaskState.RUNNING);
            return true;
        }
        // 检查是否已完成
        TaskState state = getState(taskId);
        if (state == TaskState.SUCCESS) {
            log.debug("[Idempotency] 任务已完成，跳过: taskId={}", taskId);
            return false;
        }
        log.debug("[Idempotency] 任务正在执行中: taskId={}", taskId);
        return false;
    }

    /** 使用默认超时的 tryLock */
    public boolean tryLock(String taskId) {
        return tryLock(taskId, DEFAULT_LOCK_TIMEOUT_MS);
    }

    /**
     * 标记任务成功并释放锁。
     */
    public void markSuccess(String taskId) {
        setState(taskId, TaskState.SUCCESS);
        redisTemplate.delete(LOCK_PREFIX + taskId);
    }

    /**
     * 标记任务失败并释放锁。
     */
    public void markFailed(String taskId) {
        setState(taskId, TaskState.FAILED);
        redisTemplate.delete(LOCK_PREFIX + taskId);
    }

    /**
     * 缓存任务执行结果。
     */
    public void cacheResult(String taskId, String result) {
        redisTemplate.opsForValue().set(
                RESULT_PREFIX + taskId, result,
                1, TimeUnit.HOURS);
    }

    /**
     * 获取缓存的任务执行结果。
     */
    public String getCachedResult(String taskId) {
        return redisTemplate.opsForValue().get(RESULT_PREFIX + taskId);
    }

    /**
     * 生成全局唯一的 TaskID。
     *
     * @param userId    用户 ID
     * @param feature   请求特征（如工具名+参数的哈希）
     * @return taskId = userId:featureHash:timestamp
     */
    public static String generateTaskId(String userId, String feature) {
        long timestamp = System.currentTimeMillis();
        int featureHash = Math.abs(feature.hashCode() % 100000);
        return userId + ":" + featureHash + ":" + timestamp;
    }

    private void setState(String taskId, TaskState state) {
        redisTemplate.opsForValue().set(
                STATE_PREFIX + taskId, state.name(),
                1, TimeUnit.DAYS);
    }

    private TaskState getState(String taskId) {
        String val = redisTemplate.opsForValue().get(STATE_PREFIX + taskId);
        if (val == null) return TaskState.PENDING;
        try { return TaskState.valueOf(val); }
        catch (IllegalArgumentException e) { return TaskState.PENDING; }
    }
}
