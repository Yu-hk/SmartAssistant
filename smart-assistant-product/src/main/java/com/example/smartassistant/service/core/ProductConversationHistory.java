package com.example.smartassistant.service.core;

import java.util.*;

/** Compatibility adapter for the Router's bounded, session-local legacy context envelope. */
public final class ProductConversationHistory {
    private ProductConversationHistory() { }
    public static List<String> read(Map<String, Object> input, String envelope) {
        if (input.get("conversationHistory") instanceof List<?> entries)
            return entries.stream().filter(String.class::isInstance).map(String.class::cast)
                    .skip(Math.max(0, entries.size() - 10)).map(s -> s.substring(0, Math.min(s.length(), 1000))).toList();
        if (envelope == null || envelope.length() > 16000) return List.of();
        String marker = "[商品实体历史]";
        int start = envelope.indexOf(marker);
        int end = envelope.indexOf("[商品实体历史结束]", start);
        String history;
        if (start >= 0 && end > start) history = envelope.substring(start + marker.length(), end);
        else {
            start = envelope.indexOf("[对话上下文]");
            if (start < 0) return List.of();
            history = envelope.substring(start);
        }
        List<String> roles = history.lines().map(String::trim)
                .filter(s -> s.startsWith("用户：") || s.startsWith("助手："))
                .map(s -> s.substring(0, Math.min(s.length(), 1000))).toList();
        return List.copyOf(roles.subList(Math.max(0, roles.size() - 10), roles.size()));
    }
    public static String currentQuestion(String envelope) {
        if (envelope == null) return "";
        int marker = envelope.indexOf("[对话上下文]");
        return (marker >= 0 ? envelope.substring(0, marker) : envelope).trim();
    }
}
