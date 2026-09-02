package com.example.smartassistant.consumer.service.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Distributed, user-scoped conversation gate.
 *
 * <p>One user may own one interactive session. That session may execute one turn at a time.
 * Other sessions are recorded as suspended and never reach profile/model/tool execution.</p>
 */
@Service
public class ConversationGateService {

    private static final Logger log = LoggerFactory.getLogger(ConversationGateService.class);
    private static final String PREFIX = "conversation:gate:";

    private static final DefaultRedisScript<String> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local active = redis.call('GET', KEYS[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', ARGV[7])

            if not active then
              active = ARGV[1]
              redis.call('SET', KEYS[1], active, 'PX', ARGV[5])
            elseif active == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[5])
            end

            if active ~= ARGV[1] then
              redis.call('ZADD', KEYS[3], 'NX', ARGV[4], ARGV[1])
              redis.call('HSET', KEYS[4], ARGV[1], ARGV[2])
              redis.call('PEXPIRE', KEYS[3], ARGV[8])
              redis.call('PEXPIRE', KEYS[4], ARGV[8])
              local rank = redis.call('ZRANK', KEYS[3], ARGV[1])
              return 'SESSION_SUSPENDED|' .. active .. '|' .. tostring((rank or 0) + 1) .. '|'
            end

            redis.call('ZREM', KEYS[3], ARGV[1])
            redis.call('HDEL', KEYS[4], ARGV[1])
            local running = redis.call('GET', KEYS[2])
            if not running then
              local lease = ARGV[2] .. '|' .. ARGV[3]
              redis.call('SET', KEYS[2], lease, 'PX', ARGV[6])
              redis.call('SET', KEYS[5], ARGV[1] .. '|' .. ARGV[3], 'PX', ARGV[6])
              return 'ACQUIRED|' .. active .. '|0|' .. ARGV[3]
            end

            local separator = string.find(running, '|', 1, true)
            local runningRequest = separator and string.sub(running, 1, separator - 1) or running
            local runningToken = separator and string.sub(running, separator + 1) or ''
            if runningRequest == ARGV[2] then
              redis.call('PEXPIRE', KEYS[2], ARGV[6])
              redis.call('SET', KEYS[5], ARGV[1] .. '|' .. runningToken, 'PX', ARGV[6])
              return 'REATTACHED|' .. active .. '|0|' .. runningToken
            end
            return 'REQUEST_BLOCKED|' .. active .. '|1|'
            """, String.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local running = redis.call('GET', KEYS[1])
            if running == ARGV[1] .. '|' .. ARGV[2] then
              redis.call('DEL', KEYS[1])
              redis.call('DEL', KEYS[2])
              return 1
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            if redis.call('GET', KEYS[2]) ~= ARGV[2] .. '|' .. ARGV[3] then return 0 end
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            redis.call('PEXPIRE', KEYS[2], ARGV[5])
            redis.call('PEXPIRE', KEYS[3], ARGV[5])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<String> RELEASE_BY_REQUEST_SCRIPT = new DefaultRedisScript<>("""
            local index = redis.call('GET', KEYS[1])
            if not index then return 'NOT_FOUND' end
            local separator = string.find(index, '|', 1, true)
            if not separator then return 'INVALID' end
            local session = string.sub(index, 1, separator - 1)
            local token = string.sub(index, separator + 1)
            local runningKey = ARGV[2] .. session
            local running = redis.call('GET', runningKey)
            if running == ARGV[1] .. '|' .. token then
              redis.call('DEL', runningKey)
              redis.call('DEL', KEYS[1])
              return 'RELEASED|' .. session
            end
            return 'STALE'
            """, String.class);

    private static final DefaultRedisScript<String> CLOSE_SCRIPT = new DefaultRedisScript<>("""
            local active = redis.call('GET', KEYS[1])
            if active ~= ARGV[1] then
              redis.call('ZREM', KEYS[3], ARGV[1])
              redis.call('HDEL', KEYS[4], ARGV[1])
              return 'NOT_ACTIVE|'
            end
            if redis.call('EXISTS', KEYS[2]) == 1 then return 'BUSY|' .. active end
            redis.call('DEL', KEYS[1])
            return 'CLOSED|'
            """, String.class);

    private static final DefaultRedisScript<String> RESUME_SCRIPT = new DefaultRedisScript<>("""
            local active = redis.call('GET', KEYS[1])
            if active then
              if active == ARGV[1] then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
                return 'ALREADY_ACTIVE|' .. active
              end
              return 'CONFLICT|' .. active
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
            return 'RESUMED|' .. ARGV[1]
            """, String.class);

    private static final DefaultRedisScript<Long> ROLLBACK_RESUME_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[1])
            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
            redis.call('PEXPIRE', KEYS[2], ARGV[3])
            redis.call('PEXPIRE', KEYS[3], ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(
            1, Thread.ofPlatform().daemon().name("conversation-gate-heartbeat-", 0).factory());

    @Autowired(required = false)
    private ConversationGateStateStore stateStore;

    @Value("${session.exclusive.active-ttl:30m}")
    private Duration activeTtl = Duration.ofMinutes(30);

    @Value("${session.exclusive.request-ttl:5m}")
    private Duration requestTtl = Duration.ofMinutes(5);

    @Value("${session.exclusive.suspended-ttl:10m}")
    private Duration suspendedTtl = Duration.ofMinutes(10);

    @Value("${session.exclusive.fail-closed:true}")
    private boolean failClosed = true;

    public ConversationGateService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public GateDecision acquire(String userId, String sessionId, String requestId) {
        requireText(userId, "userId");
        requireText(sessionId, "sessionId");
        requireText(requestId, "requestId");
        String token = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try {
            String result = redisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    List.of(activeKey(userId), runningKey(userId, sessionId), suspendedKey(userId),
                            suspendedDetailsKey(userId), requestIndexKey(userId, requestId)),
                    sessionId, requestId, token, Long.toString(now),
                    Long.toString(activeTtl.toMillis()), Long.toString(requestTtl.toMillis()),
                    Long.toString(now - suspendedTtl.toMillis()), Long.toString(suspendedTtl.toMillis()));
            GateDecision decision = GateDecision.parse(result, userId, sessionId, requestId);
            recordState(decision);
            return decision;
        } catch (RuntimeException error) {
            log.error("[ConversationGate] acquire failed: userId={}, sessionId={}, error={}",
                    userId, sessionId, error.getMessage());
            return failClosed
                    ? new GateDecision(GateStatus.UNAVAILABLE, userId, sessionId, requestId, null, 0, null)
                    : new GateDecision(GateStatus.ACQUIRED, userId, sessionId, requestId, sessionId, 0, token);
        }
    }

    public boolean release(GateDecision lease) {
        if (lease == null || !lease.acquired() || lease.leaseToken() == null) return false;
        try {
            Long released = redisTemplate.execute(
                    RELEASE_SCRIPT,
                    List.of(runningKey(lease.userId(), lease.sessionId()),
                            requestIndexKey(lease.userId(), lease.requestId())),
                    lease.requestId(), lease.leaseToken());
            boolean success = released != null && released == 1L;
            if (success && stateStore != null) {
                stateStore.requestCompleted(lease.userId(), lease.sessionId());
            }
            return success;
        } catch (RuntimeException error) {
            log.warn("[ConversationGate] release failed: userId={}, requestId={}, error={}",
                    lease.userId(), lease.requestId(), error.getMessage());
            return false;
        }
    }

    /** Keeps the active-session and running-turn leases alive while a blocking workflow is executing. */
    public Heartbeat heartbeat(GateDecision lease) {
        if (lease == null || !lease.acquired()) return () -> { };
        long intervalMs = Math.max(1_000L, Math.min(30_000L, requestTtl.toMillis() / 3));
        ScheduledFuture<?> future = heartbeatScheduler.scheduleAtFixedRate(
                () -> renew(lease), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    boolean renew(GateDecision lease) {
        try {
            Long renewed = redisTemplate.execute(
                    RENEW_SCRIPT,
                    List.of(activeKey(lease.userId()), runningKey(lease.userId(), lease.sessionId()),
                            requestIndexKey(lease.userId(), lease.requestId())),
                    lease.sessionId(), lease.requestId(), lease.leaseToken(),
                    Long.toString(activeTtl.toMillis()), Long.toString(requestTtl.toMillis()));
            if (renewed == null || renewed != 1L) {
                log.warn("[ConversationGate] lease renewal rejected: userId={}, sessionId={}, requestId={}",
                        lease.userId(), lease.sessionId(), lease.requestId());
                return false;
            }
            return true;
        } catch (RuntimeException error) {
            log.warn("[ConversationGate] lease renewal failed: userId={}, requestId={}, error={}",
                    lease.userId(), lease.requestId(), error.getMessage());
            return false;
        }
    }

    @PreDestroy
    void shutdownHeartbeatScheduler() {
        heartbeatScheduler.shutdownNow();
    }

    public boolean releaseByRequest(String userId, String requestId) {
        requireText(userId, "userId");
        requireText(requestId, "requestId");
        try {
            String result = redisTemplate.execute(
                    RELEASE_BY_REQUEST_SCRIPT,
                    List.of(requestIndexKey(userId, requestId)),
                    requestId, runningPrefix(userId));
            if (result != null && result.startsWith("RELEASED|")) {
                String sessionId = result.substring("RELEASED|".length());
                if (stateStore != null && !sessionId.isBlank()) {
                    stateStore.requestCompleted(userId, sessionId);
                }
                return true;
            }
            return false;
        } catch (RuntimeException error) {
            log.warn("[ConversationGate] releaseByRequest failed: userId={}, requestId={}, error={}",
                    userId, requestId, error.getMessage());
            return false;
        }
    }

    public CloseDecision close(String userId, String sessionId) {
        requireText(userId, "userId");
        requireText(sessionId, "sessionId");
        try {
            String result = redisTemplate.execute(
                    CLOSE_SCRIPT,
                    List.of(activeKey(userId), runningKey(userId, sessionId), suspendedKey(userId),
                            suspendedDetailsKey(userId)),
                    sessionId);
            CloseDecision decision = CloseDecision.parse(result);
            if (decision.status() == CloseStatus.CLOSED && stateStore != null) {
                stateStore.closed(userId, sessionId);
            }
            return decision;
        } catch (RuntimeException error) {
            log.error("[ConversationGate] close failed: userId={}, sessionId={}, error={}",
                    userId, sessionId, error.getMessage());
            return new CloseDecision(CloseStatus.UNAVAILABLE);
        }
    }

    /**
     * Explicitly restores a suspended session. The Redis script is the concurrency
     * boundary, while the durable state check prevents restoring another user's or
     * an already closed session.
     */
    public ResumeDecision resume(String userId, String sessionId) {
        requireText(userId, "userId");
        requireText(sessionId, "sessionId");
        if (stateStore == null) {
            return new ResumeDecision(ResumeStatus.UNAVAILABLE, null);
        }
        try {
            if (!stateStore.isSuspended(userId, sessionId)) {
                return new ResumeDecision(ResumeStatus.NOT_SUSPENDED, null);
            }
            String result = redisTemplate.execute(
                    RESUME_SCRIPT,
                    List.of(activeKey(userId), suspendedKey(userId), suspendedDetailsKey(userId)),
                    sessionId, Long.toString(activeTtl.toMillis()));
            ResumeDecision decision = ResumeDecision.parse(result);
            if (decision.status() == ResumeStatus.RESUMED
                    || decision.status() == ResumeStatus.ALREADY_ACTIVE) {
                try {
                    stateStore.resumed(userId, sessionId);
                } catch (RuntimeException durableStateError) {
                    rollbackResume(userId, sessionId);
                    throw durableStateError;
                }
            }
            return decision;
        } catch (RuntimeException error) {
            log.error("[ConversationGate] resume failed: userId={}, sessionId={}, error={}",
                    userId, sessionId, error.getMessage());
            return new ResumeDecision(ResumeStatus.UNAVAILABLE, null);
        }
    }

    private void rollbackResume(String userId, String sessionId) {
        try {
            redisTemplate.execute(
                    ROLLBACK_RESUME_SCRIPT,
                    List.of(activeKey(userId), suspendedKey(userId), suspendedDetailsKey(userId)),
                    sessionId, Long.toString(System.currentTimeMillis()),
                    Long.toString(suspendedTtl.toMillis()));
        } catch (RuntimeException rollbackError) {
            log.error("[ConversationGate] resume rollback failed: userId={}, sessionId={}, error={}",
                    userId, sessionId, rollbackError.getMessage());
        }
    }

    private static String userSlot(String userId) { return PREFIX + "{" + userId + "}:"; }
    private static String activeKey(String userId) { return userSlot(userId) + "active"; }
    private static String runningPrefix(String userId) { return userSlot(userId) + "running:"; }
    private static String runningKey(String userId, String sessionId) { return runningPrefix(userId) + sessionId; }
    private static String suspendedKey(String userId) { return userSlot(userId) + "suspended"; }
    private static String suspendedDetailsKey(String userId) { return userSlot(userId) + "suspended-details"; }
    private static String requestIndexKey(String userId, String requestId) {
        return userSlot(userId) + "request:" + requestId;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('|') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and must not contain '|'");
        }
    }

    private void recordState(GateDecision decision) {
        if (stateStore == null || decision.status() == GateStatus.UNAVAILABLE) return;
        try {
            stateStore.record(decision);
        } catch (RuntimeException error) {
            log.warn("[ConversationGate] durable state update failed: userId={}, sessionId={}, error={}",
                    decision.userId(), decision.sessionId(), error.getMessage());
        }
    }

    public enum GateStatus { ACQUIRED, REATTACHED, SESSION_SUSPENDED, REQUEST_BLOCKED, UNAVAILABLE }

    public record GateDecision(GateStatus status, String userId, String sessionId, String requestId,
                               String activeSessionId, int queuePosition, String leaseToken) {
        static GateDecision parse(String value, String userId, String sessionId, String requestId) {
            if (value == null || value.isBlank()) {
                return new GateDecision(GateStatus.UNAVAILABLE, userId, sessionId, requestId, null, 0, null);
            }
            String[] parts = value.split("\\|", -1);
            try {
                GateStatus status = GateStatus.valueOf(parts[0]);
                String active = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
                int position = parts.length > 2 && !parts[2].isBlank() ? Integer.parseInt(parts[2]) : 0;
                String token = parts.length > 3 && !parts[3].isBlank() ? parts[3] : null;
                return new GateDecision(status, userId, sessionId, requestId, active, position, token);
            } catch (RuntimeException ignored) {
                return new GateDecision(GateStatus.UNAVAILABLE, userId, sessionId, requestId, null, 0, null);
            }
        }

        public boolean acquired() { return status == GateStatus.ACQUIRED; }
    }

    public enum CloseStatus { CLOSED, BUSY, NOT_ACTIVE, UNAVAILABLE }

    public enum ResumeStatus { RESUMED, ALREADY_ACTIVE, CONFLICT, NOT_SUSPENDED, UNAVAILABLE }

    @FunctionalInterface
    public interface Heartbeat extends AutoCloseable {
        @Override
        void close();
    }

    public record CloseDecision(CloseStatus status) {
        static CloseDecision parse(String value) {
            if (value == null || value.isBlank()) return new CloseDecision(CloseStatus.UNAVAILABLE);
            String[] parts = value.split("\\|", -1);
            try {
                return new CloseDecision(CloseStatus.valueOf(parts[0]));
            } catch (IllegalArgumentException ignored) {
                return new CloseDecision(CloseStatus.UNAVAILABLE);
            }
        }
    }

    public record ResumeDecision(ResumeStatus status, String activeSessionId) {
        static ResumeDecision parse(String value) {
            if (value == null || value.isBlank()) {
                return new ResumeDecision(ResumeStatus.UNAVAILABLE, null);
            }
            String[] parts = value.split("\\|", -1);
            try {
                return new ResumeDecision(
                        ResumeStatus.valueOf(parts[0]),
                        parts.length > 1 && !parts[1].isBlank() ? parts[1] : null);
            } catch (IllegalArgumentException ignored) {
                return new ResumeDecision(ResumeStatus.UNAVAILABLE, null);
            }
        }
    }
}
