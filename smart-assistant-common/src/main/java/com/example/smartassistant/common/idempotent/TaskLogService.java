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
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 任务日志服务。
 *
 * <p>用于 Agent 写入操作的幂等保护。每个写操作关联唯一的 {@code requestId}，
 * 在 Redis 中维护状态机：PENDING → RUNNING → COMPLETED / FAILED。</p>
 *
 * <p>写操作执行前调用 {@link #tryStart(String, String)} 检查是否已执行过，
 * 已完成的直接返回结果，避免重复执行。</p>
 *
 * <p>Redis Key 格式：{@code a2a:task:{requestId}}</p>
 * <p>Redis Value 格式：{@code status|resultJson|timestamp}</p>
 */
@Service
public class TaskLogService {

    private static final Logger LOG = LoggerFactory.getLogger(TaskLogService.class);

    private static final String TASK_KEY_PREFIX = "a2a:task:";
    private static final long TASK_TTL_HOURS = 72;  // 任务日志保留 72 小时

    private final StringRedisTemplate redisTemplate;
    private final DistributedLock distributedLock;

    public TaskLogService(StringRedisTemplate redisTemplate, DistributedLock distributedLock) {
        this.redisTemplate = redisTemplate;
        this.distributedLock = distributedLock;
    }

    // ═══════════════════════════════════════════════════════════
    // 任务状态枚举
    // ═══════════════════════════════════════════════════════════

    public enum TaskStatus {
        PENDING, RUNNING, COMPLETED, FAILED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 核心 API
    // ═══════════════════════════════════════════════════════════

    /**
     * 尝试开始一个任务（幂等检查）。
     *
     * <p>如果任务已存在且已完成，返回已有结果，不重复执行。</p>
     * <p>如果任务不存在或处于 PENDING/FAILED 状态，标记为 RUNNING 并返回空 Optional。</p>
     * <p>如果任务处于 RUNNING 状态（其他实例正在执行），返回空 Optional 表示跳过。</p>
     *
     * @param requestId  全局唯一请求 ID
     * @param taskType   任务类型（如 "order:create"、"order:cancel"）
     * @return 任务日志（已完成的任务包含结果）
     */
    public TaskLog tryStart(String requestId, String taskType) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }

        String key = TASK_KEY_PREFIX + requestId;
        String existing = redisTemplate.opsForValue().get(key);

        if (existing != null) {
            TaskLog existingLog = TaskLog.fromString(existing);
            if (existingLog != null) {
                if (existingLog.status == TaskStatus.COMPLETED) {
                    LOG.info("[TaskLog] 幂等命中: requestId={}, taskType={}, status=COMPLETED, 直接返回结果",
                            requestId, taskType);
                    return existingLog;
                }
                if (existingLog.status == TaskStatus.RUNNING) {
                    LOG.warn("[TaskLog] 任务正在执行中: requestId={}, taskType={}, 跳过重复触发",
                            requestId, taskType);
                    return existingLog;  // 正在执行，返回但不重复开始
                }
                // FAILED 状态：允许重新执行
                LOG.info("[TaskLog] 任务之前失败，允许重试: requestId={}, taskType={}", requestId, taskType);
            }
        }

        // 新任务或失败任务：标记为 RUNNING
        TaskLog newLog = new TaskLog(requestId, taskType, TaskStatus.RUNNING, null, LocalDateTime.now());
        redisTemplate.opsForValue().set(key, newLog.toString(), Duration.ofHours(TASK_TTL_HOURS));
        LOG.info("[TaskLog] 任务开始: requestId={}, taskType={}", requestId, taskType);
        return newLog;
    }

    /**
     * 标记任务为已完成。
     *
     * @param requestId 请求 ID
     * @param result    执行结果（用于幂等返回）
     */
    public void markCompleted(String requestId, String result) {
        updateStatus(requestId, TaskStatus.COMPLETED, result);
    }

    /**
     * 标记任务为失败。
     *
     * @param requestId 请求 ID
     * @param errorMsg  错误信息
     */
    public void markFailed(String requestId, String errorMsg) {
        updateStatus(requestId, TaskStatus.FAILED, errorMsg);
    }

    /**
     * 获取任务日志。
     */
    public TaskLog get(String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        String key = TASK_KEY_PREFIX + requestId;
        String raw = redisTemplate.opsForValue().get(key);
        return raw != null ? TaskLog.fromString(raw) : null;
    }

    /**
     * 带锁的幂等执行：仅在任务未完成时执行 action。
     *
     * @param requestId  全局唯一请求 ID
     * @param taskType   任务类型
     * @param lockKey    分布式锁 key（如 "order:create:ORD-001"）
     * @param action     执行动作（返回结果字符串）
     * @return 任务结果（已完成的直接返回缓存结果）
     */
    public String executeIfNotDone(String requestId, String taskType, String lockKey,
                                   java.util.function.Supplier<String> action) {
        // 1. 幂等检查
        TaskLog log = tryStart(requestId, taskType);
        if (log == null) return null;
        if (log.status == TaskStatus.COMPLETED) {
            return log.result;  // 已有结果，直接返回
        }
        if (log.status == TaskStatus.RUNNING && !log.isFresh()) {
            // 非本实例开始的任务且正在执行 → 跳过
            return null;
        }

        // 2. 获取分布式锁
        var lock = distributedLock.getLock(lockKey);
        if (!lock.tryLock()) {
            LOG.warn("[TaskLog] 获取分布式锁失败: lockKey={}, requestId={}", lockKey, requestId);
            return null;
        }

        try {
            // 3. 执行
            String result = action.get();
            markCompleted(requestId, result);
            return result;
        } catch (Exception e) {
            markFailed(requestId, e.getMessage());
            throw e;
        } finally {
            lock.unlock();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════════════════════

    private void updateStatus(String requestId, TaskStatus status, String data) {
        if (requestId == null || requestId.isBlank()) return;
        String key = TASK_KEY_PREFIX + requestId;
        TaskLog log = new TaskLog(requestId, null, status, data, LocalDateTime.now());
        redisTemplate.opsForValue().set(key, log.toString(), Duration.ofHours(TASK_TTL_HOURS));
        LOG.info("[TaskLog] 任务更新: requestId={}, status={}", requestId, status);
    }

    // ═══════════════════════════════════════════════════════════
    // 任务日志模型
    // ═══════════════════════════════════════════════════════════

    /**
     * 任务日志记录。
     */
    public static class TaskLog {
        final String requestId;
        final String taskType;
        final TaskStatus status;
        final String result;
        final LocalDateTime timestamp;

        private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        private static final String SEP = "|";

        TaskLog(String requestId, String taskType, TaskStatus status, String result, LocalDateTime timestamp) {
            this.requestId = requestId;
            this.taskType = taskType;
            this.status = status;
            this.result = result;
            this.timestamp = timestamp;
        }

        /** 判断是否为本实例刚创建的任务（用于检测并发执行） */
        boolean isFresh() {
            return timestamp != null
                    && Duration.between(timestamp, LocalDateTime.now()).toSeconds() < 5;
        }

        public String getRequestId() { return requestId; }
        public TaskStatus getStatus() { return status; }
        public String getResult() { return result; }
        public LocalDateTime getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return status.name() + SEP + (result != null ? result : "") + SEP + timestamp.format(FMT);
        }

        static TaskLog fromString(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String[] parts = raw.split("\\|", 3);
            if (parts.length < 1) return null;
            TaskStatus status;
            try {
                status = TaskStatus.valueOf(parts[0]);
            } catch (IllegalArgumentException e) {
                return null;
            }
            String result = parts.length > 1 ? (parts[1].isEmpty() ? null : parts[1]) : null;
            LocalDateTime ts = parts.length > 2 ? LocalDateTime.parse(parts[2], FMT) : LocalDateTime.now();
            return new TaskLog(null, null, status, result, ts);
        }
    }
}
