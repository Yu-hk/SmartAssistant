package com.example.smartassistant.consumer.service.dispatch;

import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.config.ChatDispatchProperties;
import com.example.smartassistant.consumer.service.sentiment.TurnInsight;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PriorityRoutingDispatcher {
    private final ChatDispatchProperties properties;
    private final ChatDispatchPublisher publisher;
    private final ChatDispatchStore store;
    private final RouterClient router;
    public PriorityRoutingDispatcher(ChatDispatchProperties properties, ChatDispatchPublisher publisher,
                                     ChatDispatchStore store, RouterClient router) {
        this.properties = properties; this.publisher = publisher; this.store = store; this.router = router;
    }
    public boolean enabled() { return properties.enabled(); }
    public long queueWaitMs() { return enabled() ? properties.queueWaitMs() : 0; }

    public Map<String, Object> route(String question, String userId, String sessionId, String requestId,
                                     TurnInsight insight, long executionWaitMs, Runnable onPoll) {
        if (!enabled()) return router.callRouterRaw(question, userId, sessionId, requestId, !insight.bypassAnswerCache());
        ChatDispatchCommand command = store.reserve(ChatDispatchCommand.create(Long.valueOf(userId),
                sessionId, requestId, question, insight, System.currentTimeMillis() + properties.queueWaitMs()));
        Map<String, Object> previous = store.result(command);
        if (previous != null) return previous;
        if ("QUEUED".equals(store.status(command))) {
            try { publisher.publish(command); }
            catch (RuntimeException uncertain) {
                // If still queued, atomically cancel before any consumer can run it. Never direct-call Router.
                store.finish(command, "QUEUED", "REJECTED", failure("PUBLISH_UNCONFIRMED", "请求未确认进入处理队列，请稍后重试。"));
            }
        }
        long budget = Math.min(properties.resultWaitMs(), Math.max(1000, executionWaitMs) + properties.queueWaitMs());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budget);
        while (System.nanoTime() < deadline) {
            Map<String, Object> result = store.result(command);
            if (result != null) return result;
            if (System.currentTimeMillis() >= command.expiresAt()) {
                store.finish(command, "QUEUED", "EXPIRED", failure("QUEUE_TIMEOUT", "排队等待超时，本轮尚未执行，请稍后重试。"));
            }
            if (onPoll != null) onPoll.run();
            try { Thread.sleep(100); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                store.finish(command, "QUEUED", "CANCELLED", cancelled());
                return failure("WAIT_INTERRUPTED", "等待已中断；已开始的任务请查询原请求结果，避免重复操作。");
            }
        }
        store.finish(command, "QUEUED", "EXPIRED", failure("QUEUE_TIMEOUT", "排队等待超时，本轮尚未执行。"));
        Map<String, Object> result = store.result(command);
        return result != null ? result : failure("EXECUTION_UNCONFIRMED", "任务已进入处理，但结果尚未确认。请查询原请求结果，避免重复提交业务操作。");
    }

    public void cancelQueued(Long userId, String requestId) {
        if (!enabled()) return;
        ChatDispatchCommand command = store.ownedCommand(userId, requestId);
        if (command != null) store.finish(command, "QUEUED", "CANCELLED", cancelled());
    }

    public Map<String, Object> ownedStatus(Long userId, String requestId) {
        ChatDispatchCommand command = store.ownedCommand(userId, requestId);
        if (command == null) return Map.of("requestId", requestId, "status", "NOT_FOUND");
        // Reclaim a queued request even if its original HTTP/SSE waiter disconnected.
        if (System.currentTimeMillis() >= command.expiresAt()) {
            store.finish(command, "QUEUED", "EXPIRED", failure("QUEUE_TIMEOUT", "排队超时，本轮尚未执行。"));
        }
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("requestId", requestId);
        response.put("status", store.status(command));
        Map<String, Object> result = store.result(command);
        if (result != null) {
            response.put("reply", result.getOrDefault("result", ""));
            // Never expose raw tool arguments or internal Router diagnostics through the lookup endpoint.
            com.example.smartassistant.consumer.service.infrastructure.TokenUsageExtractor.extract(result).copyTo(response);
        }
        return response;
    }

    public static Map<String, Object> failure(String reason, String text) {
        return Map.of("result", text, "error", reason, "workflowStatus", "FAILED", "executionMode", "BUILTIN");
    }
    public static Map<String, Object> cancelled() {
        return Map.of("result", "", "cancelled", true, "workflowStatus", "CANCELLED");
    }
}
