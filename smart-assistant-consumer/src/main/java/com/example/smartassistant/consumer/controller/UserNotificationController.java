package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.notification.UserNotificationSseService;
import com.example.smartassistant.consumer.service.notification.UserNotificationStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/** Authenticated user notification inbox and real-time stream. */
@RestController
@RequestMapping("/api/notifications")
public class UserNotificationController {

    private final UserNotificationStore store;
    private final UserNotificationSseService sseService;

    public UserNotificationController(UserNotificationStore store,
                                      UserNotificationSseService sseService) {
        this.store = store;
        this.sseService = sseService;
    }

    @GetMapping
    public ResponseEntity<?> unread(@RequestHeader("X-User-Id") Long userId,
                                    @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(store.unread(userId, limit));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<?> unreadCount(@RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(Map.of("count", store.unreadCount(userId)));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestHeader("X-User-Id") Long userId) {
        return sseService.subscribe(userId);
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<?> markRead(@PathVariable String notificationId,
                                      @RequestHeader("X-User-Id") Long userId) {
        return store.markRead(notificationId, userId)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }
}
