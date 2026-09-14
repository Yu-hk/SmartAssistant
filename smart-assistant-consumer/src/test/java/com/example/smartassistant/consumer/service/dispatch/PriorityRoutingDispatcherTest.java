package com.example.smartassistant.consumer.service.dispatch;

import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.config.ChatDispatchProperties;
import com.example.smartassistant.consumer.service.sentiment.TurnInsight;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PriorityRoutingDispatcherTest {
    private final ChatDispatchStore store = mock(ChatDispatchStore.class);
    private final ChatDispatchPublisher publisher = mock(ChatDispatchPublisher.class);
    private final RouterClient router = mock(RouterClient.class);
    private final PriorityRoutingDispatcher dispatcher = new PriorityRoutingDispatcher(
            new ChatDispatchProperties(true, 1000, 2000, 1, 100), publisher, store, router);
    private final TurnInsight insight = TurnInsight.unknown("TIMEOUT", 750);
    private Map<String, Object> route() { return dispatcher.route("查订单", "42", "session", "request", insight, 1000, null); }
    private void reservation() { when(store.reserve(any())).thenAnswer(call -> call.getArgument(0)); }

    @Test void firstCallPublishesButNeverDirectlyExecutesRouter() {
        reservation();
        when(store.status(any())).thenReturn("QUEUED");
        when(store.result(any())).thenReturn(null, Map.of("result", "已查询", "totalTokens", 100));
        assertEquals(100, route().get("totalTokens"));
        verify(publisher).publish(any());
        verifyNoInteractions(router);
    }
    @Test void completedRetryReturnsOriginalResultWithoutRepublishing() {
        reservation();
        when(store.result(any())).thenReturn(Map.of("result", "已查询"));
        assertEquals("已查询", route().get("result"));
        verifyNoInteractions(router, publisher);
    }
    @Test void uncertainPublishCancelsOnlyQueuedAttemptAndNeverFallsBackToHttp() {
        reservation();
        when(store.status(any())).thenReturn("QUEUED");
        when(store.result(any())).thenReturn(null, PriorityRoutingDispatcher.failure("PUBLISH_UNCONFIRMED", "未确认"));
        doThrow(new IllegalStateException("no confirm")).when(publisher).publish(any());
        assertEquals("PUBLISH_UNCONFIRMED", route().get("error"));
        verify(store).finish(any(), eq("QUEUED"), eq("REJECTED"), anyMap());
        verifyNoInteractions(router);
    }
    @Test void busyAccountCannotEnterBroker() {
        when(store.reserve(any())).thenThrow(new IllegalStateException("busy"));
        assertThrows(IllegalStateException.class, this::route);
        verifyNoInteractions(router, publisher);
    }
    @Test void onlyOwnerCanCancelQueuedRequest() {
        when(store.ownedCommand(43L, "request")).thenThrow(new SecurityException());
        assertThrows(SecurityException.class, () -> dispatcher.cancelQueued(43L, "request"));
        verify(store, never()).finish(any(), any(), any(), any());
    }
    @Test void expiredQueuedAttemptDoesNotRunAfterWaiterTimeout() {
        when(store.reserve(any())).thenReturn(new ChatDispatchCommand(42L, "session", "request", "查订单", false, 0, 1));
        when(store.status(any())).thenReturn("QUEUED");
        when(store.result(any())).thenReturn(null, null, PriorityRoutingDispatcher.failure("QUEUE_TIMEOUT", "超时"));
        assertEquals("QUEUE_TIMEOUT", route().get("error"));
        verify(store).finish(any(), eq("QUEUED"), eq("EXPIRED"), anyMap());
        verifyNoInteractions(router);
    }

    @Test void lookupOnlyReturnsOwnerSafeResultFields() {
        var command = new ChatDispatchCommand(42L, "session", "request", "查订单", false, 0, Long.MAX_VALUE);
        when(store.ownedCommand(42L, "request")).thenReturn(command);
        when(store.status(command)).thenReturn("COMPLETED");
        when(store.result(command)).thenReturn(Map.of("result", "已查询", "totalTokens", 100,
                "toolCalls", java.util.List.of(Map.of("arguments", "private-data")), "error", "internal-diagnostic"));
        var result = dispatcher.ownedStatus(42L, "request");
        assertEquals("已查询", result.get("reply"));
        assertEquals(100L, result.get("totalTokens"));
        assertFalse(result.toString().contains("private-data"));
        assertFalse(result.toString().contains("internal-diagnostic"));
        when(store.ownedCommand(43L, "request")).thenThrow(new SecurityException());
        assertThrows(SecurityException.class, () -> dispatcher.ownedStatus(43L, "request"));
    }
}
