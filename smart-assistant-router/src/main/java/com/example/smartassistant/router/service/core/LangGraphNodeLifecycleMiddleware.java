package com.example.smartassistant.router.service.core;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.hook.NodeHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * LangGraph4j-native workflow middleware.
 *
 * <p>This is the Java counterpart of LangChain's {@code wrap_model_call} /
 * {@code wrap_tool_call} lifecycle middleware at the graph-node boundary. It
 * keeps cancellation propagation and node telemetry outside business node
 * implementations, including nodes dispatched on parallel executors.</p>
 */
@Component
public class LangGraphNodeLifecycleMiddleware
        implements NodeHook.WrapCall<LangGraphRouteExecutionService.RouterGraphState> {

    static final String LATENCY_METRIC = "smart_assistant_workflow_node_duration";
    static final String INVOCATION_METRIC = "smart_assistant_workflow_node_invocations_total";

    private static final Logger log = LoggerFactory.getLogger(
            LangGraphNodeLifecycleMiddleware.class);

    private final WorkflowCancellationService cancellationService;
    private final Timer nodeLatency;
    private final Counter succeeded;
    private final Counter failed;
    private final Counter cancelled;

    public LangGraphNodeLifecycleMiddleware(WorkflowCancellationService cancellationService,
                                            MeterRegistry meterRegistry) {
        this.cancellationService = cancellationService;
        this.nodeLatency = Timer.builder(LATENCY_METRIC)
                .description("LangGraph4j workflow node execution latency")
                .tag("service", "router-service")
                .register(meterRegistry);
        this.succeeded = invocationCounter(meterRegistry, "success");
        this.failed = invocationCounter(meterRegistry, "failed");
        this.cancelled = invocationCounter(meterRegistry, "cancelled");
    }

    @Override
    public CompletableFuture<Map<String, Object>> applyWrap(
            String nodeId,
            LangGraphRouteExecutionService.RouterGraphState state,
            RunnableConfig config,
            AsyncNodeActionWithConfig<LangGraphRouteExecutionService.RouterGraphState> action) {
        Long userId = longValue(state.<Object>value(
                LangGraphRouteExecutionService.USER_ID).orElse(null));
        String requestId = stringValue(state.<Object>value(
                LangGraphRouteExecutionService.REQUEST_ID).orElse(null));
        long startedAt = System.nanoTime();
        WorkflowCancellationService.Registration registration = () -> { };
        try {
            registration = canRegister(requestId, userId)
                    ? cancellationService.register(requestId, userId)
                    : registration;
            CompletableFuture<Map<String, Object>> invocation = action.apply(state, config);
            if (invocation == null) {
                throw new IllegalStateException("LangGraph4j node returned no future: " + nodeId);
            }
            WorkflowCancellationService.Registration activeRegistration = registration;
            return invocation.whenComplete((ignored, error) ->
                    finish(nodeId, startedAt, activeRegistration, error));
        } catch (Throwable error) {
            finish(nodeId, startedAt, registration, error);
            return CompletableFuture.failedFuture(error);
        }
    }

    private void finish(String nodeId, long startedAt,
                        WorkflowCancellationService.Registration registration,
                        Throwable error) {
        registration.close();
        long elapsedNanos = System.nanoTime() - startedAt;
        nodeLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
        if (error == null) {
            succeeded.increment();
            log.debug("[LangGraph4jMiddleware] node completed: nodeId={}, elapsedMs={}",
                    nodeId, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        } else if (isCancellation(error)) {
            cancelled.increment();
            log.info("[LangGraph4jMiddleware] node cancelled: nodeId={}, elapsedMs={}",
                    nodeId, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        } else {
            failed.increment();
            log.warn("[LangGraph4jMiddleware] node failed: nodeId={}, elapsedMs={}, error={}",
                    nodeId, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), error.getMessage());
        }
    }

    private static Counter invocationCounter(MeterRegistry registry, String outcome) {
        return Counter.builder(INVOCATION_METRIC)
                .description("LangGraph4j workflow node invocations")
                .tag("service", "router-service")
                .tag("outcome", outcome)
                .register(registry);
    }

    private static boolean canRegister(String requestId, Long userId) {
        return requestId != null && !requestId.isBlank() && userId != null && userId > 0;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof WorkflowCancelledException) return true;
            if (current.getCause() == current) return false;
            current = current.getCause();
        }
        return false;
    }
}
