package com.example.smartassistant.routing.contract;

/** Canonical Redis keys shared by routing producers and consumers. */
public final class RoutingKeys {

    public static final String FULL_DECISION_PREFIX = "a2a:route:full-decision:";
    public static final String TASK_ANALYSIS_PREFIX = "a2a:task-analysis:";
    public static final String SSE_EVENTS_PREFIX = "routing:sse:events:";
    public static final String SSE_STREAM_PREFIX = "routing:sse:stream:";
    public static final String EXECUTION_GRAPH_PREFIX = "routing:execution-graph:";
    public static final String CANCELLATION_PREFIX = "routing:cancellation:";
    public static final String EXECUTION_OWNER_PREFIX = "routing:execution-owner:";
    public static final String USER_PROFILE_CONTEXT_PREFIX = "routing:user-profile-context:";
    public static final String USER_PROFILE_CANDIDATE_PREFIX = "routing:user-profile-candidate:";

    /** Request-scoped profile hand-off states shared by Consumer and Router. */
    public static final String USER_PROFILE_PENDING = "PENDING";
    public static final String USER_PROFILE_EMPTY = "EMPTY";
    public static final String USER_PROFILE_FAILED = "FAILED";
    public static final String USER_PROFILE_READY_PREFIX = "READY\n";
    public static final String USER_PROFILE_INPUT = "userProfile";

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

    public static String executionGraph(String requestId) {
        return EXECUTION_GRAPH_PREFIX + requireRequestId(requestId);
    }

    public static String cancellation(String requestId) {
        return CANCELLATION_PREFIX + requireRequestId(requestId);
    }

    public static String executionOwner(String requestId) {
        return EXECUTION_OWNER_PREFIX + requireRequestId(requestId);
    }

    public static String userProfileContext(String requestId) {
        return USER_PROFILE_CONTEXT_PREFIX + requireRequestId(requestId);
    }

    public static String userProfileCandidate(String requestId) {
        return USER_PROFILE_CANDIDATE_PREFIX + requireRequestId(requestId);
    }


    private static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        return requestId;
    }
}
