package com.example.smartassistant.consumer.service.notification;

import java.time.Instant;

/** User-visible, durably stored notification. */
public record UserNotification(
        String id,
        String type,
        String title,
        String content,
        String sessionId,
        String requestId,
        String status,
        Instant createdAt) {
}
