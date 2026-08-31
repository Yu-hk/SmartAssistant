package com.example.smartassistant.router.service.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Process-independent workflow execution lease with a heartbeat.
 * A crashed Router stops renewing the key, allowing a recovery worker to take over.
 */
@Component
public class WorkflowExecutionLeaseService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionLeaseService.class);
    private static final String KEY_PREFIX = "a2a:workflow:{recovery}:lease:";
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = script("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = script("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """);

    private final StringRedisTemplate redisTemplate;
    private final long leaseMs;
    private final ScheduledExecutorService heartbeatExecutor;
    private final ConcurrentHashMap<String, LocalLease> fallback = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Lease> ownedLeases = new ConcurrentHashMap<>();

    public WorkflowExecutionLeaseService(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Value("${router.graph.recovery.execution-lease-ms:90000}") long leaseMs) {
        this.redisTemplate = redisTemplate;
        this.leaseMs = Math.max(3_000L, leaseMs);
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("workflow-lease-heartbeat").factory());
    }

    /** Runs an execution only while this process owns its renewable lease. */
    public <T> T executeWithLease(String requestId, Supplier<T> action) {
        try (Lease lease = acquire(requestId)) {
            if (lease == null) {
                throw new WorkflowAlreadyRunningException(requestId);
            }
            return action.get();
        }
    }

    public Lease acquire(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required for a workflow lease");
        }
        String owner = UUID.randomUUID().toString();
        boolean acquired;
        if (redisTemplate == null) {
            long now = System.currentTimeMillis();
            LocalLease candidate = new LocalLease(owner, now + leaseMs);
            fallback.compute(requestId, (key, current) ->
                    current == null || current.expiresAtEpochMs <= now ? candidate : current);
            acquired = fallback.get(requestId) == candidate;
        } else {
            acquired = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                    key(requestId), owner, Duration.ofMillis(leaseMs)));
        }
        if (!acquired) return null;

        Lease lease = new Lease(requestId, owner);
        ownedLeases.put(requestId, lease);
        long heartbeatMs = Math.max(1_000L, leaseMs / 3L);
        lease.heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> renew(lease), heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        return lease;
    }

    /** Fail-closed ownership check used before every durable checkpoint mutation. */
    public void assertOwned(String requestId) {
        Lease lease = ownedLeases.get(requestId);
        if (lease == null || !lease.isValid() || !renew(lease)) {
            throw new WorkflowLeaseLostException(requestId);
        }
    }

    public boolean isActive(String requestId) {
        if (requestId == null || requestId.isBlank()) return false;
        if (redisTemplate != null) {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(requestId)));
        }
        long now = System.currentTimeMillis();
        LocalLease current = fallback.get(requestId);
        if (current == null) return false;
        if (current.expiresAtEpochMs > now) return true;
        fallback.remove(requestId, current);
        return false;
    }

    private boolean renew(Lease lease) {
        if (lease.closed.get() || !lease.valid.get()) return false;
        try {
            boolean renewed;
            if (redisTemplate == null) {
                long now = System.currentTimeMillis();
                LocalLease current = fallback.get(lease.requestId);
                renewed = current != null && current.owner.equals(lease.owner);
                if (renewed) fallback.put(lease.requestId, new LocalLease(lease.owner, now + leaseMs));
            } else {
                Long result = redisTemplate.execute(RENEW_SCRIPT, List.of(key(lease.requestId)),
                        lease.owner, String.valueOf(leaseMs));
                renewed = Long.valueOf(1L).equals(result);
            }
            if (!renewed) {
                lease.valid.set(false);
                log.warn("[WorkflowRecovery] execution lease lost: requestId={}", lease.requestId);
            }
            return renewed;
        } catch (RuntimeException e) {
            lease.valid.set(false);
            log.warn("[WorkflowRecovery] execution lease heartbeat failed: requestId={}, error={}",
                    lease.requestId, e.getMessage());
            return false;
        }
    }

    private void release(Lease lease) {
        if (lease.heartbeat != null) lease.heartbeat.cancel(false);
        ownedLeases.remove(lease.requestId, lease);
        if (redisTemplate == null) {
            LocalLease current = fallback.get(lease.requestId);
            if (current != null && current.owner.equals(lease.owner)) {
                fallback.remove(lease.requestId, current);
            }
            return;
        }
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key(lease.requestId)), lease.owner);
        } catch (RuntimeException e) {
            log.warn("[WorkflowRecovery] execution lease release failed: requestId={}, error={}",
                    lease.requestId, e.getMessage());
        }
    }

    private static String key(String requestId) {
        return KEY_PREFIX + requestId;
    }

    private static DefaultRedisScript<Long> script(String source) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(Long.class);
        return script;
    }

    @Override
    public void destroy() {
        heartbeatExecutor.shutdownNow();
    }

    public final class Lease implements AutoCloseable {
        private final String requestId;
        private final String owner;
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private ScheduledFuture<?> heartbeat;

        private Lease(String requestId, String owner) {
            this.requestId = requestId;
            this.owner = owner;
        }

        public boolean isValid() { return valid.get() && !closed.get(); }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) release(this);
        }
    }

    private record LocalLease(String owner, long expiresAtEpochMs) {}

    public static class WorkflowAlreadyRunningException extends IllegalStateException {
        public WorkflowAlreadyRunningException(String requestId) {
            super("Workflow execution lease is already owned: requestId=" + requestId);
        }
    }

    public static class WorkflowLeaseLostException extends IllegalStateException {
        public WorkflowLeaseLostException(String requestId) {
            super("Workflow execution lease was lost: requestId=" + requestId);
        }
    }
}
