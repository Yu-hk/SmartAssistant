package com.example.smartassistant.common.governance;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Request/session-scoped counters used by both model and tool invocation paths. */
public final class InvocationBudgetRegistry {

    private static final InvocationBudgetRegistry SHARED = new InvocationBudgetRegistry();

    private final Cache<String, AtomicInteger> requestModel = cache(Duration.ofMinutes(15));
    private final Cache<String, AtomicInteger> sessionModel = cache(Duration.ofHours(2));
    private final Cache<String, AtomicInteger> requestTool = cache(Duration.ofMinutes(15));
    private final Cache<String, AtomicInteger> sessionTool = cache(Duration.ofHours(2));

    public static InvocationBudgetRegistry shared() { return SHARED; }

    public BudgetUsage acquireModel(InvocationIdentity identity, int requestLimit, int sessionLimit) {
        return acquire("model", identity, requestLimit, sessionLimit, requestModel, sessionModel);
    }

    public BudgetUsage acquireTool(InvocationIdentity identity, int requestLimit, int sessionLimit) {
        return acquire("tool", identity, requestLimit, sessionLimit, requestTool, sessionTool);
    }

    private synchronized BudgetUsage acquire(String kind, InvocationIdentity identity,
                                              int requestLimit, int sessionLimit,
                                              Cache<String, AtomicInteger> requests,
                                              Cache<String, AtomicInteger> sessions) {
        AtomicInteger request = requests.get(identity.requestId(), ignored -> new AtomicInteger());
        AtomicInteger session = sessions.get(identity.sessionId(), ignored -> new AtomicInteger());
        int nextRequest = request.get() + 1;
        int nextSession = session.get() + 1;
        if (requestLimit > 0 && nextRequest > requestLimit) {
            throw new CallBudgetExceededException(kind, "request", identity.requestId(), requestLimit);
        }
        if (sessionLimit > 0 && nextSession > sessionLimit) {
            throw new CallBudgetExceededException(kind, "session", identity.sessionId(), sessionLimit);
        }
        request.incrementAndGet();
        session.incrementAndGet();
        return new BudgetUsage(nextRequest, nextSession, requestLimit, sessionLimit);
    }

    public void clear() {
        requestModel.invalidateAll();
        sessionModel.invalidateAll();
        requestTool.invalidateAll();
        sessionTool.invalidateAll();
    }

    private static Cache<String, AtomicInteger> cache(Duration expiry) {
        return Caffeine.newBuilder().expireAfterAccess(expiry).maximumSize(100_000).build();
    }

    public record BudgetUsage(int requestCalls, int sessionCalls, int requestLimit, int sessionLimit) {}
}
