package com.example.smartassistant.consumer.service.core;

import com.example.smartassistant.consumer.config.ChatQueueConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestQueueServiceTest {

    @Test
    void releasesImmediateSessionSlotWhenRequestCompletes() {
        RequestQueueService service = service(1);

        assertEquals(RequestQueueService.SlotResult.ACQUIRED,
                service.tryAcquireWithQueue("request-1", "session-a", 5));
        assertEquals(RequestQueueService.SlotResult.QUEUE_FULL,
                service.tryAcquireWithQueue("request-2", "session-a", 5));

        service.complete("request-1");

        assertEquals(RequestQueueService.SlotResult.ACQUIRED,
                service.tryAcquireWithQueue("request-3", "session-a", 5));
        service.complete("request-3");
    }

    @Test
    void completingUnknownOrQueuedRequestDoesNotInflateGlobalSemaphore() {
        RequestQueueService service = service(1);
        service.complete("unknown");

        assertEquals(RequestQueueService.SlotResult.ACQUIRED,
                service.tryAcquireWithQueue("request-1", "session-a", 5));
        assertEquals(RequestQueueService.SlotResult.QUEUED,
                service.tryAcquireWithQueue("request-2", "session-b", 5));

        service.complete("request-2");
        assertEquals(0, service.getQueueSize());
        assertEquals(1, service.getActiveCount());
        service.complete("request-1");
    }

    private static RequestQueueService service(int maxConcurrent) {
        ChatQueueConfig config = new ChatQueueConfig();
        config.setMaxConcurrent(maxConcurrent);
        config.setMaxQueueSize(10);
        config.setQueueTimeoutMs(50);
        RequestQueueService service = new RequestQueueService(config);
        ReflectionTestUtils.setField(service, "sessionMaxConcurrency", 1);
        ReflectionTestUtils.setField(service, "priorityEnabled", true);
        return service;
    }
}
