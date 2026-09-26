package com.example.smartassistant.consumer.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Captures only user-visible answer text, not tools, reasoning or event metadata. */
final class VisibleReplyAccumulator {
    private static final int MAX_CHARS = 16_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final StringBuilder text = new StringBuilder();

    void accept(String type, String payload) {
        if ((!"text".equals(type) && !"response".equals(type)) || payload == null) return;
        try {
            JsonNode event = JSON.readTree(payload);
            JsonNode value = event.get("content");
            if (value == null || !value.isTextual()) value = event.get("message");
            if (value == null || !value.isTextual() || value.asText().isBlank()) return;
            String content = value.asText();
            if ("response".equals(type)) text.setLength(0);
            int remaining = MAX_CHARS - text.length();
            if (remaining > 0) text.append(content, 0, Math.min(content.length(), remaining));
        } catch (Exception ignored) {
            // Invalid progress events must not interrupt the primary response stream.
        }
    }

    String text() { return text.toString(); }
}
