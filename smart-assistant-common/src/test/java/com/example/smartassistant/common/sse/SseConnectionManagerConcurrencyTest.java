package com.example.smartassistant.common.sse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SseConnectionManager 并发限流")
class SseConnectionManagerConcurrencyTest {

    @Test
    @Timeout(10)
    @DisplayName("并发争抢不突破单用户上限，释放后名额可复用")
    void concurrentRegistrationNeverExceedsLimitAndReleasedSlotsAreReusable() throws Exception {
        int maxPerUser = 3;
        int contenders = 12;
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.size(anyString())).thenReturn(0L);
        SseConnectionManager manager = new SseConnectionManager(redisTemplate, maxPerUser);
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < contenders; i++) {
                String requestId = "request-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return manager.tryRegister("user-1", requestId);
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<String> acceptedRequestIds = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                if (futures.get(i).get(5, TimeUnit.SECONDS)) {
                    acceptedRequestIds.add("request-" + i);
                }
            }

            assertEquals(maxPerUser, acceptedRequestIds.size());
            assertFalse(manager.tryRegister("user-1", "overflow"));

            acceptedRequestIds.forEach(requestId -> manager.release("user-1", requestId));
            assertTrue(manager.tryRegister("user-1", "replacement"));
            manager.release("user-1", "replacement");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
