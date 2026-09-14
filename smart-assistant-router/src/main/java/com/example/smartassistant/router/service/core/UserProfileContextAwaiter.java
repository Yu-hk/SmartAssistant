package com.example.smartassistant.router.service.core;

import com.example.smartassistant.routing.contract.RoutingKeys;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.concurrent.*;

/** Optional context: one bounded selection per request, including Redis I/O and executor queue time. */
@Service
public class UserProfileContextAwaiter {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserProfileContextAwaiter.class);
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;
    @Value("${router.user-profile.wait-timeout-ms:500}")
    private long waitTimeoutMs = 500;
    @Value("${router.user-profile.poll-interval-ms:25}")
    private long pollIntervalMs = 25;

    // No caller-runs fallback: slow/unavailable Redis must not hold the Product thread.
    private final ExecutorService readers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon().name("profile-context-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final Cache<SelectionKey, Selection> selections = Caffeine.newBuilder()
            .maximumSize(10000).expireAfterWrite(Duration.ofHours(1)).build();

    public String await(String requestId) {
        return await(requestId, null);
    }

    public String await(String requestId, Long userId) {
        checkInterrupted();
        if (redisTemplate == null || requestId == null || requestId.isBlank()) return "";
        Selection selection = selections.get(new SelectionKey(userId, requestId), key -> start(key.requestId()));
        try {
            long remaining = selection.deadline - System.nanoTime();
            if (!selection.result.isDone() && remaining <= 0) expire(selection);
            String profile = selection.result.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
            checkInterrupted();
            return profile;
        } catch (TimeoutException timeout) {
            expire(selection);
            return selection.result.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while selecting optional profile");
        } catch (ExecutionException unavailable) {
            expire(selection);
            return "";
        }
    }

    private Selection start(String requestId) {
        Selection selection = new Selection(System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0, Math.min(2000, waitTimeoutMs))));
        try {
            selection.task = readers.submit(() -> readUntilReady(requestId, selection));
        } catch (RejectedExecutionException overloaded) {
            selection.result.complete("");
            log.info("[UserProfile] Optional context skipped: reader overloaded");
        }
        return selection;
    }

    private void readUntilReady(String requestId, Selection selection) {
        try {
            while (!selection.result.isDone() && System.nanoTime() < selection.deadline) {
                String state = redisTemplate.opsForValue().get(RoutingKeys.userProfileContext(requestId));
                // Even a late successful Redis read cannot extend the request's budget.
                if (System.nanoTime() >= selection.deadline) break;
                if (state != null && state.startsWith(RoutingKeys.USER_PROFILE_READY_PREFIX)) {
                    selection.result.complete(state.substring(RoutingKeys.USER_PROFILE_READY_PREFIX.length()));
                    return;
                }
                if (state != null && !RoutingKeys.USER_PROFILE_PENDING.equals(state)) break;
                // Missing state may mean Consumer's asynchronous preparation has not started yet.
                long remaining = selection.deadline - System.nanoTime();
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(Math.min(remaining,
                        TimeUnit.MILLISECONDS.toNanos(Math.max(1, pollIntervalMs))));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException unavailable) {
            log.warn("[UserProfile] Optional context unavailable: requestId={}, type={}",
                    requestId, unavailable.getClass().getSimpleName());
        } finally {
            selection.result.complete("");
        }
    }

    private static void expire(Selection selection) {
        if (selection.result.complete("") && selection.task != null) selection.task.cancel(true);
    }
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Request interrupted");
    }
    @PreDestroy
    public void close() { readers.shutdownNow(); selections.invalidateAll(); }
    private static final class Selection {
        final long deadline;
        final CompletableFuture<String> result = new CompletableFuture<>();
        volatile Future<?> task;
        Selection(long deadline) { this.deadline = deadline; }
    }
    private record SelectionKey(Long userId, String requestId) { }
}
