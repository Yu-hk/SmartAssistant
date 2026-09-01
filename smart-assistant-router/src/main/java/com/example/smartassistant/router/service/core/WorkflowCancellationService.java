package com.example.smartassistant.router.service.core;

import com.example.smartassistant.routing.contract.RoutingKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates workflow cancellation across HTTP, Router graph and parallel node threads.
 * Redis carries the signal across instances; the local registry provides immediate interruption.
 */
@Service
public class WorkflowCancellationService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowCancellationService.class);
    private static final Duration SIGNAL_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentHashMap<String, ActiveExecution> activeExecutions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CancellationSignal> localSignals = new ConcurrentHashMap<>();

    public WorkflowCancellationService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisTemplate = redisProvider.getIfAvailable();
    }

    /** Registers the current thread so a cancellation can interrupt blocking model or Agent calls. */
    public Registration register(String requestId, Long userId) {
        requireIdentity(requestId, userId);
        purgeExpiredSignals();
        ActiveExecution active = activeExecutions.compute(requestId, (key, existing) -> {
            if (existing != null && !existing.userId.equals(userId)) {
                throw new SecurityException("Workflow request belongs to another user");
            }
            return existing != null ? existing : new ActiveExecution(userId);
        });
        Thread thread = Thread.currentThread();
        active.threads.add(thread);
        storeOwner(requestId, userId);
        try {
            throwIfCancellationRequested(requestId, userId);
        } catch (RuntimeException error) {
            unregister(requestId, active, thread);
            throw error;
        }
        return () -> unregister(requestId, active, thread);
    }

    /** Marks a request cancelled and interrupts every locally active execution thread. */
    public boolean requestCancellation(String requestId, Long userId) {
        requireIdentity(requestId, userId);
        purgeExpiredSignals();
        Long owner = resolveOwner(requestId);
        if (owner != null && !owner.equals(userId)) {
            throw new SecurityException("Workflow request belongs to another user");
        }
        localSignals.put(requestId, new CancellationSignal(userId, System.currentTimeMillis()));
        writeValue(RoutingKeys.cancellation(requestId), userId.toString(), SIGNAL_TTL);

        ActiveExecution active = activeExecutions.get(requestId);
        if (active != null) {
            if (!active.userId.equals(userId)) {
                throw new SecurityException("Workflow request belongs to another user");
            }
            active.threads.forEach(Thread::interrupt);
            log.info("[WorkflowCancellation] interrupted requestId={}, threads={}",
                    requestId, active.threads.size());
            return true;
        }
        log.info("[WorkflowCancellation] recorded cancellation before registration: requestId={}", requestId);
        return false;
    }

    public boolean isCancellationRequested(String requestId, Long userId) {
        if (requestId == null || requestId.isBlank() || userId == null || userId <= 0) return false;
        CancellationSignal local = localSignals.get(requestId);
        if (local != null && !expired(local.createdAt()) && local.userId().equals(userId)) return true;
        String remoteOwner = readValue(RoutingKeys.cancellation(requestId));
        return remoteOwner != null && remoteOwner.equals(userId.toString());
    }

    public void throwIfCancellationRequested(String requestId, Long userId) {
        if (isCancellationRequested(requestId, userId)) {
            throw new WorkflowCancelledException(requestId);
        }
    }

    /** Removes ownership metadata after the route has stopped; the short-lived signal remains for Consumer. */
    public void complete(String requestId, Long userId) {
        if (requestId == null || requestId.isBlank()) return;
        ActiveExecution active = activeExecutions.get(requestId);
        if (active == null || userId == null || active.userId.equals(userId)) {
            deleteKey(RoutingKeys.executionOwner(requestId));
        }
    }

    private void unregister(String requestId, ActiveExecution active, Thread thread) {
        active.threads.remove(thread);
        if (active.threads.isEmpty()) activeExecutions.remove(requestId, active);
    }

    private Long resolveOwner(String requestId) {
        ActiveExecution active = activeExecutions.get(requestId);
        if (active != null) return active.userId;
        String stored = readValue(RoutingKeys.executionOwner(requestId));
        if (stored == null || stored.isBlank()) return null;
        try {
            return Long.valueOf(stored);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void storeOwner(String requestId, Long userId) {
        writeValue(RoutingKeys.executionOwner(requestId), userId.toString(), SIGNAL_TTL);
    }

    private void writeValue(String key, String value, Duration ttl) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception error) {
            log.warn("[WorkflowCancellation] Redis write failed: key={}, error={}", key, error.getMessage());
        }
    }

    private String readValue(String key) {
        if (redisTemplate == null) return null;
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception error) {
            log.warn("[WorkflowCancellation] Redis read failed: key={}, error={}", key, error.getMessage());
            return null;
        }
    }

    private void deleteKey(String key) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.delete(key);
        } catch (Exception error) {
            log.warn("[WorkflowCancellation] Redis delete failed: key={}, error={}", key, error.getMessage());
        }
    }

    private void purgeExpiredSignals() {
        localSignals.entrySet().removeIf(entry -> expired(entry.getValue().createdAt()));
    }

    private static boolean expired(long createdAt) {
        return System.currentTimeMillis() - createdAt > SIGNAL_TTL.toMillis();
    }

    private static void requireIdentity(String requestId, Long userId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("authenticated user id must be positive");
        }
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private static final class ActiveExecution {
        private final Long userId;
        private final Set<Thread> threads = ConcurrentHashMap.newKeySet();

        private ActiveExecution(Long userId) {
            this.userId = userId;
        }
    }

    private record CancellationSignal(Long userId, long createdAt) {
    }
}
