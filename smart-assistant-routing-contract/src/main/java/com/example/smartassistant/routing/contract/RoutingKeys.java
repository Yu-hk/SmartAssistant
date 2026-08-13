package com.example.smartassistant.routing.contract;

/** Canonical Redis keys shared by routing producers and consumers. */
public final class RoutingKeys {

    public static final String FULL_DECISION_PREFIX = "a2a:route:full-decision:";
    public static final String TASK_ANALYSIS_PREFIX = "a2a:task-analysis:";
    public static final String SSE_EVENTS_PREFIX = "routing:sse:events:";
    public static final String SSE_STREAM_PREFIX = "routing:sse:stream:";

    private RoutingKeys() {
    }

    public static String fullDecision(String requestId) {
        return FULL_DECISION_PREFIX + requireRequestId(requestId);
    }

    public static String decisionNotification(String requestId) {
        return FULL_DECISION_PREFIX + "notify:" + requireRequestId(requestId);
    }

    public static String taskAnalysis(String requestId) {
        return TASK_ANALYSIS_PREFIX + requireRequestId(requestId);
    }

    public static String sseEvents(String requestId) {
        return SSE_EVENTS_PREFIX + requireRequestId(requestId);
    }

    public static String sseStream(String requestId) {
        return SSE_STREAM_PREFIX + requireRequestId(requestId);
    }

    private static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        return requestId;
    }
}
