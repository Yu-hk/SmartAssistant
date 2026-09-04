/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.order.infrastructure.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis-backed idempotent task execution with leases and fencing tokens.
 *
 * <p>Every write operation is claimed atomically. A crashed worker stops owning
 * the task when its lease expires; a replacement worker can then claim it with a
 * larger fencing token. Completion from the old worker is rejected, preventing
 * stale processes from overwriting the recovered result.</p>
 */
@Service
@ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
public class TaskLogService {

    private static final Logger LOG = LoggerFactory.getLogger(TaskLogService.class);

    private static final String TASK_KEY_PREFIX = "a2a:task:{";
    private static final String LEGACY_TASK_KEY_PREFIX = "a2a:task:";
    private static final long TASK_TTL_MS = Duration.ofHours(72).toMillis();
    private static final long DEFAULT_LEASE_MS = Duration.ofMinutes(2).toMillis();

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CLAIM_SCRIPT = script("""
            local stateType = redis.call('TYPE', KEYS[1])['ok']
            if stateType == 'string' then
              local legacy = redis.call('GET', KEYS[1]) or ''
              if string.sub(legacy, 1, 10) == 'COMPLETED|' then
                local result = string.match(legacy, '^COMPLETED|(.*)|[^|]*$') or ''
                return {'COMPLETED', result, '0'}
              end
              redis.call('DEL', KEYS[1])
            elseif stateType == 'hash' then
              local status = redis.call('HGET', KEYS[1], 'status')
              if status == 'COMPLETED' then
                return {'COMPLETED', redis.call('HGET', KEYS[1], 'result') or '',
                        redis.call('HGET', KEYS[1], 'fencingToken') or '0'}
              end
            end

            local leaseOwner = redis.call('GET', KEYS[2])
            if leaseOwner then
              return {'BUSY', '', redis.call('HGET', KEYS[1], 'fencingToken') or '0'}
            end

            local fence = redis.call('INCR', KEYS[3])
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[4])
            redis.call('HSET', KEYS[1],
                    'status', 'RUNNING',
                    'taskType', ARGV[2],
                    'result', '',
                    'updatedAtEpochMs', ARGV[3],
                    'ownerToken', ARGV[1],
                    'fencingToken', tostring(fence),
                    'leaseUntilEpochMs', tostring(tonumber(ARGV[3]) + tonumber(ARGV[4])))
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            redis.call('PEXPIRE', KEYS[3], ARGV[5])
            return {'CLAIMED', '', tostring(fence)}
            """, List.class);

    private static final DefaultRedisScript<Long> TERMINAL_SCRIPT = script("""
            if redis.call('TYPE', KEYS[1])['ok'] ~= 'hash' then return 0 end
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
            if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[1] then return 0 end
            if redis.call('HGET', KEYS[1], 'fencingToken') ~= ARGV[2] then return 0 end
            redis.call('HSET', KEYS[1],
                    'status', ARGV[3],
                    'result', ARGV[4],
                    'updatedAtEpochMs', ARGV[5],
                    'leaseUntilEpochMs', '0')
            redis.call('DEL', KEYS[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[6])
            redis.call('PEXPIRE', KEYS[3], ARGV[6])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RENEW_SCRIPT = script("""
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
            if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[1] then return 0 end
            if redis.call('HGET', KEYS[1], 'fencingToken') ~= ARGV[2] then return 0 end
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            redis.call('HSET', KEYS[1],
                    'updatedAtEpochMs', ARGV[3],
                    'leaseUntilEpochMs', tostring(tonumber(ARGV[3]) + tonumber(ARGV[4])))
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            redis.call('PEXPIRE', KEYS[3], ARGV[5])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final DistributedLock distributedLock;
    private final long leaseMs;

    @Autowired
    public TaskLogService(StringRedisTemplate redisTemplate, DistributedLock distributedLock) {
        this(redisTemplate, distributedLock, DEFAULT_LEASE_MS);
    }

    TaskLogService(StringRedisTemplate redisTemplate, DistributedLock distributedLock, long leaseMs) {
        this.redisTemplate = redisTemplate;
        this.distributedLock = distributedLock;
        this.leaseMs = Math.max(1_000L, leaseMs);
    }

    public enum TaskStatus {
        PENDING, RUNNING, COMPLETED, FAILED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED;
        }
    }

    /** Atomically claims a task, returns its completed result, or reports it busy. */
    public TaskLog tryStart(String requestId, String taskType) {
        if (requestId == null || requestId.isBlank()) return null;
        TaskLog legacy = readLegacy(requestId);
        if (legacy != null && legacy.status == TaskStatus.COMPLETED) return legacy;
        if (legacy != null) redisTemplate.delete(legacyTaskKey(requestId));
        String ownerToken = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        List<?> response = redisTemplate.execute(CLAIM_SCRIPT,
                keys(requestId), ownerToken, safe(taskType), String.valueOf(now),
                String.valueOf(leaseMs), String.valueOf(TASK_TTL_MS));
        if (response == null || response.isEmpty()) {
            throw new IllegalStateException("Atomic task claim returned no result");
        }

        String outcome = String.valueOf(response.getFirst());
        String result = response.size() > 1 ? emptyToNull(String.valueOf(response.get(1))) : null;
        long fence = response.size() > 2 ? parseLong(response.get(2)) : 0L;
        if ("COMPLETED".equals(outcome)) {
            LOG.info("[TaskLog] 幂等命中: requestId={}, taskType={}, status=COMPLETED",
                    requestId, taskType);
            return new TaskLog(requestId, taskType, TaskStatus.COMPLETED, result,
                    now, null, fence, 0L, false);
        }
        if ("BUSY".equals(outcome)) {
            LOG.info("[TaskLog] 任务租约仍有效: requestId={}, taskType={}", requestId, taskType);
            return new TaskLog(requestId, taskType, TaskStatus.RUNNING, null,
                    now, null, fence, 0L, false);
        }
        if (!"CLAIMED".equals(outcome)) {
            throw new IllegalStateException("Unknown atomic task claim result: " + outcome);
        }

        LOG.info("[TaskLog] 任务领取成功: requestId={}, taskType={}, fencingToken={}",
                requestId, taskType, fence);
        return new TaskLog(requestId, taskType, TaskStatus.RUNNING, null,
                now, ownerToken, fence, now + leaseMs, true);
    }

    /** Renews the lease of a claimed task. Stale owners cannot renew it. */
    public boolean renewLease(TaskLog claim) {
        if (claim == null || !claim.claimed || claim.ownerToken == null) return false;
        long now = System.currentTimeMillis();
        Long renewed = redisTemplate.execute(RENEW_SCRIPT, keys(claim.requestId),
                claim.ownerToken, String.valueOf(claim.fencingToken), String.valueOf(now),
                String.valueOf(leaseMs), String.valueOf(TASK_TTL_MS));
        return Long.valueOf(1L).equals(renewed);
    }

    /** Returns the persisted task state, including lease and fencing metadata. */
    public TaskLog get(String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        String key = taskKey(requestId);
        var dataType = redisTemplate.type(key);
        String type = dataType != null ? dataType.code() : "none";
        if ("string".equals(type)) {
            String raw = redisTemplate.opsForValue().get(key);
            return TaskLog.fromLegacyString(requestId, raw);
        }
        if (!"hash".equals(type)) return readLegacy(requestId);
        var values = redisTemplate.<String, String>opsForHash().entries(key);
        if (values.isEmpty()) return null;
        TaskStatus status;
        try {
            status = TaskStatus.valueOf(values.getOrDefault("status", "FAILED"));
        } catch (IllegalArgumentException e) {
            status = TaskStatus.FAILED;
        }
        long updatedAt = parseLong(values.get("updatedAtEpochMs"));
        return new TaskLog(requestId, values.get("taskType"), status,
                emptyToNull(values.get("result")), updatedAt,
                emptyToNull(values.get("ownerToken")), parseLong(values.get("fencingToken")),
                parseLong(values.get("leaseUntilEpochMs")), false);
    }

    /**
     * Executes an action at least once while making its durable business effect idempotent.
     * A completed result is replayed; an active lease returns {@code null}; an expired lease
     * is atomically taken over with a larger fencing token.
     */
    public String executeIfNotDone(String requestId, String taskType, String lockKey,
                                   Supplier<String> action) {
        TaskLog claim = tryStart(requestId, taskType);
        if (claim == null) return null;
        if (claim.status == TaskStatus.COMPLETED) return claim.result;
        if (!claim.claimed) return null;

        var lock = distributedLock.getLock(lockKey, leaseMs);
        if (!lock.tryLock()) {
            markTerminal(claim, TaskStatus.FAILED, "Failed to acquire domain lock");
            return null;
        }

        try {
            String result = action.get();
            if (!markTerminal(claim, TaskStatus.COMPLETED, result)) {
                throw new StaleTaskOwnerException(requestId, claim.fencingToken);
            }
            return result;
        } catch (RuntimeException e) {
            markTerminal(claim, TaskStatus.FAILED, e.getMessage());
            throw e;
        } finally {
            lock.unlock();
        }
    }

    private boolean markTerminal(TaskLog claim, TaskStatus status, String result) {
        if (claim == null || !claim.claimed || claim.ownerToken == null) return false;
        Long updated = redisTemplate.execute(TERMINAL_SCRIPT, keys(claim.requestId),
                claim.ownerToken, String.valueOf(claim.fencingToken), status.name(), safe(result),
                String.valueOf(System.currentTimeMillis()), String.valueOf(TASK_TTL_MS));
        boolean accepted = Long.valueOf(1L).equals(updated);
        if (accepted) {
            LOG.info("[TaskLog] 任务更新: requestId={}, status={}, fencingToken={}",
                    claim.requestId, status, claim.fencingToken);
        } else {
            LOG.warn("[TaskLog] 拒绝过期执行者更新: requestId={}, status={}, fencingToken={}",
                    claim.requestId, status, claim.fencingToken);
        }
        return accepted;
    }

    private static List<String> keys(String requestId) {
        String base = taskBase(requestId);
        return List.of(base + ":state", base + ":lease", base + ":fence");
    }

    private static String taskKey(String requestId) {
        return taskBase(requestId) + ":state";
    }

    private static String taskBase(String requestId) {
        return TASK_KEY_PREFIX + requestId + "}";
    }

    private static String legacyTaskKey(String requestId) {
        return LEGACY_TASK_KEY_PREFIX + requestId;
    }

    private TaskLog readLegacy(String requestId) {
        String key = legacyTaskKey(requestId);
        var dataType = redisTemplate.type(key);
        if (dataType == null || !"string".equals(dataType.code())) return null;
        return TaskLog.fromLegacyString(requestId, redisTemplate.opsForValue().get(key));
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() || "null".equals(value) ? null : value;
    }

    private static long parseLong(Object value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static <T> DefaultRedisScript<T> script(String source, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(resultType);
        return script;
    }

    public static class TaskLog {
        private static final DateTimeFormatter LEGACY_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

        private final String requestId;
        private final String taskType;
        private final TaskStatus status;
        private final String result;
        private final long updatedAtEpochMs;
        private final String ownerToken;
        private final long fencingToken;
        private final long leaseUntilEpochMs;
        private final boolean claimed;

        TaskLog(String requestId, String taskType, TaskStatus status, String result,
                long updatedAtEpochMs, String ownerToken, long fencingToken,
                long leaseUntilEpochMs, boolean claimed) {
            this.requestId = requestId;
            this.taskType = taskType;
            this.status = status;
            this.result = result;
            this.updatedAtEpochMs = updatedAtEpochMs;
            this.ownerToken = ownerToken;
            this.fencingToken = fencingToken;
            this.leaseUntilEpochMs = leaseUntilEpochMs;
            this.claimed = claimed;
        }

        public String getRequestId() { return requestId; }
        public String getTaskType() { return taskType; }
        public TaskStatus getStatus() { return status; }
        public String getResult() { return result; }
        public LocalDateTime getTimestamp() {
            return LocalDateTime.ofEpochSecond(updatedAtEpochMs / 1_000L,
                    (int) (updatedAtEpochMs % 1_000L) * 1_000_000, ZoneOffset.UTC);
        }
        public long getFencingToken() { return fencingToken; }
        public long getLeaseUntilEpochMs() { return leaseUntilEpochMs; }
        public boolean isClaimed() { return claimed; }

        static TaskLog fromLegacyString(String requestId, String raw) {
            if (raw == null || raw.isBlank()) return null;
            String[] parts = raw.split("\\|", 3);
            TaskStatus status;
            try {
                status = TaskStatus.valueOf(parts[0]);
            } catch (IllegalArgumentException e) {
                return null;
            }
            String result = parts.length > 1 ? emptyToNull(parts[1]) : null;
            long timestamp = System.currentTimeMillis();
            if (parts.length > 2) {
                try {
                    timestamp = LocalDateTime.parse(parts[2], LEGACY_FMT)
                            .toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (RuntimeException ignored) {
                    // Preserve compatibility with malformed legacy entries.
                }
            }
            return new TaskLog(requestId, null, status, result, timestamp,
                    null, 0L, 0L, false);
        }
    }

    /** Indicates that a worker completed after its lease was lost or superseded. */
    public static class StaleTaskOwnerException extends IllegalStateException {
        public StaleTaskOwnerException(String requestId, long fencingToken) {
            super("Task lease lost before completion: requestId=" + requestId
                    + ", fencingToken=" + fencingToken);
        }
    }
}
