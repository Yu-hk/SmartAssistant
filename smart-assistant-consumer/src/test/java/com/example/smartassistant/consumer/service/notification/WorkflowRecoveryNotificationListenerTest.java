package com.example.smartassistant.consumer.service.notification;

import com.example.smartassistant.common.recovery.WorkflowRecoveryCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRecoveryNotificationListenerTest {

    @Test
    void persistsAndPushesANewCompletionEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        UserNotificationStore store = mock(UserNotificationStore.class);
        UserNotificationSseService sse = mock(UserNotificationSseService.class);
        WorkflowRecoveryNotificationListener listener =
                new WorkflowRecoveryNotificationListener(objectMapper, store, sse);
        WorkflowRecoveryCompletedEvent event = event();
        UserNotification notification = notification();
        when(store.storeRecovery(event))
                .thenReturn(new UserNotificationStore.StoredNotification(notification, true));

        listener.receive(new Message(objectMapper.writeValueAsBytes(event)));

        verify(store).storeRecovery(event);
        verify(sse).publish(7L, notification);
    }

    @Test
    void duplicateDeliveryDoesNotPushAnotherRealtimeNotification() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        UserNotificationStore store = mock(UserNotificationStore.class);
        UserNotificationSseService sse = mock(UserNotificationSseService.class);
        WorkflowRecoveryNotificationListener listener =
                new WorkflowRecoveryNotificationListener(objectMapper, store, sse);
        WorkflowRecoveryCompletedEvent event = event();
        when(store.storeRecovery(event))
                .thenReturn(new UserNotificationStore.StoredNotification(notification(), false));

        listener.receive(new Message(objectMapper.writeValueAsBytes(event)));

        verify(sse, never()).publish(7L, notification());
    }

    private static WorkflowRecoveryCompletedEvent event() {
        return new WorkflowRecoveryCompletedEvent(
                "recovery-1", "request-1", 7L, "SUCCEEDED", "恢复结果",
                Instant.parse("2026-08-27T08:00:00Z"));
    }

    private static UserNotification notification() {
        return new UserNotification(
                "notification-1", "WORKFLOW_RECOVERY", "回答已恢复", "恢复结果",
                "session-1", "request-1", "UNREAD",
                Instant.parse("2026-08-27T08:00:00Z"));
    }
}
