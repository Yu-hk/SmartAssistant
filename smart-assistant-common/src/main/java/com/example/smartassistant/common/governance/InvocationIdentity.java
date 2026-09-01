package com.example.smartassistant.common.governance;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

/** Resolves stable request/session identities shared by Advisors and ToolGateway. */
public record InvocationIdentity(String requestId, String sessionId) {

    public static InvocationIdentity resolve(Map<String, Object> context) {
        String request = first(context, "requestId", "traceId");
        if (blank(request)) request = firstMdc("requestId", "traceId");
        if (blank(request)) request = "anonymous-" + UUID.randomUUID();

        String session = first(context, "sessionId", "conversationId", "chatId");
        if (blank(session)) session = firstMdc("sessionId", "conversationId", "chatId");
        if (blank(session)) session = request;
        return new InvocationIdentity(request, session);
    }

    private static String first(Map<String, Object> context, String... keys) {
        if (context == null) return null;
        for (String key : keys) {
            Object value = context.get(key);
            if (value != null && !value.toString().isBlank()) return value.toString();
        }
        return null;
    }

    private static String firstMdc(String... keys) {
        for (String key : keys) {
            String value = MDC.get(key);
            if (!blank(value)) return value;
        }
        return null;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
