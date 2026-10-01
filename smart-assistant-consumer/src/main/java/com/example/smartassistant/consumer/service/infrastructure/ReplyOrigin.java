package com.example.smartassistant.consumer.service.infrastructure;

import java.util.Map;

/** Explicit reply provenance, independent of token totals and tool counts. */
public final class ReplyOrigin {
    private ReplyOrigin() {}

    public static Boolean fromDecision(Map<String, Object> decision) {
        return decision != null && decision.get("fromCache") instanceof Boolean value ? value : null;
    }

    public static String auditMethod(boolean streaming, Boolean fromCache) {
        if (fromCache == null) return streaming ? "STREAM_ROUTER_SERVICE" : "ROUTER_SERVICE";
        return (streaming ? "STREAM_" : "ROUTER_") + (fromCache ? "CACHE" : "LIVE");
    }

    public static Boolean fromAuditMethod(String method) {
        if ("STREAM_CACHE".equals(method) || "ROUTER_CACHE".equals(method)) return true;
        if ("STREAM_LIVE".equals(method) || "ROUTER_LIVE".equals(method)) return false;
        return null;
    }
}
