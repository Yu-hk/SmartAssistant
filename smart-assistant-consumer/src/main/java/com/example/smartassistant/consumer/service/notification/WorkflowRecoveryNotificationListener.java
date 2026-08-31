package com.example.smartassistant.consumer.service.notification;

import com.example.smartassistant.common.recovery.WorkflowRecoveryCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** Consumes durable recovery events, persists them, then notifies online clients. */
@Service
public class WorkflowRecoveryNotificationListener {

    private final ObjectMapper objectMapper;
    private final UserNotificationStore store;
    private final UserNotificationSseService sseService;

    public WorkflowRecoveryNotificationListener(ObjectMapper objectMapper,
                                                UserNotificationStore store,
                                                UserNotificationSseService sseService) {
        this.objectMapper = objectMapper;
        this.store = store;
        this.sseService = sseService;
    }

    @RabbitListener(queues = "${workflow.recovery.notifications.queue}")
    public void receive(Message message) throws IOException {
        WorkflowRecoveryCompletedEvent event = objectMapper.readValue(
                message.getBody(), WorkflowRecoveryCompletedEvent.class);
        if (event.recoveryId() == null || event.recoveryId().isBlank()
                || event.requestId() == null || event.requestId().isBlank()
                || event.userId() == null || !"SUCCEEDED".equals(event.status())) {
            throw new IllegalArgumentException("Invalid workflow recovery completion event");
        }
        UserNotificationStore.StoredNotification stored = store.storeRecovery(event);
        if (stored.created()) {
            sseService.publish(event.userId(), stored.notification());
        }
    }
}
