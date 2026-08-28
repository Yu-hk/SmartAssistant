package com.example.smartassistant.consumer.service.notification;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Maintains user-scoped SSE connections for real-time notifications. */
@Service
public class UserNotificationSseService {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emitters =
            new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> remove(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data("ready"));
        } catch (IOException error) {
            cleanup.run();
            emitter.completeWithError(error);
        }
        return emitter;
    }

    public void publish(Long userId, UserNotification notification) {
        List<SseEmitter> current = emitters.getOrDefault(userId, new CopyOnWriteArrayList<>());
        for (SseEmitter emitter : current) {
            try {
                emitter.send(SseEmitter.event().id(notification.id())
                        .name("notification").data(notification));
            } catch (IOException | IllegalStateException error) {
                remove(userId, emitter);
                emitter.complete();
            }
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> current = emitters.get(userId);
        if (current == null) return;
        current.remove(emitter);
        if (current.isEmpty()) emitters.remove(userId, current);
    }
}
