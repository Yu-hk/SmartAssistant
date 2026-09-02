package com.example.smartassistant.router.service.core;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangGraphNodeLifecycleMiddlewareTest {

    @Test
    void supportsClassBasedProxyUsedByServiceInterceptors() {
        LangGraphNodeLifecycleMiddleware middleware = new LangGraphNodeLifecycleMiddleware(
                cancellationService(), new SimpleMeterRegistry());
        ProxyFactory proxyFactory = new ProxyFactory(middleware);
        proxyFactory.setProxyTargetClass(true);

        assertInstanceOf(LangGraphNodeLifecycleMiddleware.class, proxyFactory.getProxy());
    }

    @Test
    void wrapsNodeWithNativeTelemetryAndReleasesCancellationRegistration() {
        WorkflowCancellationService cancellation = cancellationService();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LangGraphNodeLifecycleMiddleware middleware =
                new LangGraphNodeLifecycleMiddleware(cancellation, registry);
        var state = state(42L, "request-success");

        Map<String, Object> output = middleware.applyWrap("product_query", state,
                RunnableConfig.builder().threadId("request-success").build(),
                (ignoredState, ignoredConfig) ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                Map.of("answer", "ok"))).join();

        assertEquals("ok", output.get("answer"));
        assertEquals(1.0, registry.get(LangGraphNodeLifecycleMiddleware.INVOCATION_METRIC)
                .tag("outcome", "success").counter().count());
        assertEquals(1L, registry.get(LangGraphNodeLifecycleMiddleware.LATENCY_METRIC)
                .timer().count());
        assertFalse(cancellation.requestCancellation("request-success", 42L),
                "completed hook must remove the node thread from the active registry");
    }

    @Test
    void stopsNodeBeforeInvocationWhenWorkflowWasCancelled() {
        WorkflowCancellationService cancellation = cancellationService();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LangGraphNodeLifecycleMiddleware middleware =
                new LangGraphNodeLifecycleMiddleware(cancellation, registry);
        AtomicBoolean invoked = new AtomicBoolean();
        cancellation.requestCancellation("request-cancelled", 42L);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> middleware.applyWrap("order_create", state(42L, "request-cancelled"),
                        RunnableConfig.builder().threadId("request-cancelled").build(),
                        (ignoredState, ignoredConfig) -> {
                            invoked.set(true);
                            return java.util.concurrent.CompletableFuture.completedFuture(Map.of());
                        }).join());

        assertInstanceOf(WorkflowCancelledException.class, failure.getCause());
        assertFalse(invoked.get());
        assertEquals(1.0, registry.get(LangGraphNodeLifecycleMiddleware.INVOCATION_METRIC)
                .tag("outcome", "cancelled").counter().count());
        assertTrue(registry.get(LangGraphNodeLifecycleMiddleware.LATENCY_METRIC)
                .timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) >= 0);
    }

    private static LangGraphRouteExecutionService.RouterGraphState state(
            Long userId, String requestId) {
        return new LangGraphRouteExecutionService.RouterGraphState(Map.of(
                LangGraphRouteExecutionService.USER_ID, userId,
                LangGraphRouteExecutionService.REQUEST_ID, requestId));
    }

    @SuppressWarnings("unchecked")
    private static WorkflowCancellationService cancellationService() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new WorkflowCancellationService(provider);
    }
}
