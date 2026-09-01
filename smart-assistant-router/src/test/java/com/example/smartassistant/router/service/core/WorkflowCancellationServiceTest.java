package com.example.smartassistant.router.service.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowCancellationServiceTest {

    @Test
    void interruptsAllLocallyRegisteredExecutionThreads() throws Exception {
        WorkflowCancellationService service = serviceWithoutRedis();
        CountDownLatch registered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        Thread worker = new Thread(() -> {
            try (var ignored = service.register("cancel-active", 42L)) {
                registered.countDown();
                try {
                    Thread.sleep(10_000L);
                } catch (InterruptedException expected) {
                    interrupted.set(true);
                }
            }
        });
        worker.start();
        assertTrue(registered.await(2, TimeUnit.SECONDS));

        assertTrue(service.requestCancellation("cancel-active", 42L));
        worker.join(2_000L);

        assertTrue(interrupted.get());
        assertFalse(worker.isAlive());
        assertTrue(service.isCancellationRequested("cancel-active", 42L));
    }

    @Test
    void cancellationIssuedBeforeRegistrationStopsTheWorkflowAtEntry() {
        WorkflowCancellationService service = serviceWithoutRedis();
        assertFalse(service.requestCancellation("cancel-race", 42L));

        assertThrows(WorkflowCancelledException.class,
                () -> service.register("cancel-race", 42L));
    }

    @Test
    void rejectsCancellationFromAnotherUser() {
        WorkflowCancellationService service = serviceWithoutRedis();
        try (var ignored = service.register("owned-request", 42L)) {
            assertThrows(SecurityException.class,
                    () -> service.requestCancellation("owned-request", 7L));
        }
    }

    @SuppressWarnings("unchecked")
    private static WorkflowCancellationService serviceWithoutRedis() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new WorkflowCancellationService(provider);
    }
}
