package com.example.smartassistant.consumer.service.session;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "RUN_REDIS_INTEGRATION_TESTS", matches = "true")
class ConversationGateRedisIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static ConversationGateService gate;

    @BeforeAll
    static void setUpRedis() {
        int port = Integer.parseInt(System.getenv().getOrDefault("TEST_REDIS_PORT", "6389"));
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        clearTestKeys();
        gate = new ConversationGateService(redisTemplate);
        ReflectionTestUtils.setField(gate, "activeTtl", Duration.ofMinutes(5));
        ReflectionTestUtils.setField(gate, "requestTtl", Duration.ofMinutes(1));
        ReflectionTestUtils.setField(gate, "suspendedTtl", Duration.ofMinutes(1));
        ConversationGateStateStore stateStore = mock(ConversationGateStateStore.class);
        when(stateStore.isSuspended("900003", "session-b")).thenReturn(true);
        when(stateStore.isSuspended("900003", "session-c")).thenReturn(true);
        ReflectionTestUtils.setField(gate, "stateStore", stateStore);
    }

    @AfterAll
    static void tearDownRedis() {
        if (gate != null) gate.shutdownHeartbeatScheduler();
        clearTestKeys();
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @Test
    void twentySessionsForOneUserProduceExactlyOneOwner() throws Exception {
        List<ConversationGateService.GateDecision> decisions = race(20, index ->
                gate.acquire("900001", "session-" + index, "request-" + index));

        assertEquals(1, decisions.stream().filter(ConversationGateService.GateDecision::acquired).count());
        assertEquals(19, decisions.stream()
                .filter(decision -> decision.status() == ConversationGateService.GateStatus.SESSION_SUSPENDED)
                .count());
        decisions.stream().filter(ConversationGateService.GateDecision::acquired).forEach(gate::release);
    }

    @Test
    void twentyTurnsInOneSessionProduceExactlyOneRunningTurn() throws Exception {
        List<ConversationGateService.GateDecision> decisions = race(20, index ->
                gate.acquire("900002", "same-session", "request-" + index));

        assertEquals(1, decisions.stream().filter(ConversationGateService.GateDecision::acquired).count());
        assertEquals(19, decisions.stream()
                .filter(decision -> decision.status() == ConversationGateService.GateStatus.REQUEST_BLOCKED)
                .count());
        decisions.stream().filter(ConversationGateService.GateDecision::acquired).forEach(gate::release);
    }

    @Test
    void closingActiveSessionWaitsForUserToChooseWhichSuspendedSessionToResume() {
        ConversationGateService.GateDecision active =
                gate.acquire("900003", "session-a", "request-a");
        gate.release(active);
        assertEquals(ConversationGateService.GateStatus.SESSION_SUSPENDED,
                gate.acquire("900003", "session-b", "request-b").status());
        assertEquals(ConversationGateService.GateStatus.SESSION_SUSPENDED,
                gate.acquire("900003", "session-c", "request-c").status());

        assertEquals(ConversationGateService.CloseStatus.CLOSED,
                gate.close("900003", "session-a").status());
        assertEquals(ConversationGateService.ResumeStatus.RESUMED,
                gate.resume("900003", "session-c").status());

        ConversationGateService.ResumeDecision conflict = gate.resume("900003", "session-b");
        assertEquals(ConversationGateService.ResumeStatus.CONFLICT, conflict.status());
        assertEquals("session-c", conflict.activeSessionId());
    }

    private static List<ConversationGateService.GateDecision> race(
            int concurrency, IndexedAcquire acquire) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<ConversationGateService.GateDecision>> tasks = new ArrayList<>();
            for (int index = 0; index < concurrency; index++) {
                int current = index;
                tasks.add(() -> {
                    ready.countDown();
                    start.await();
                    return acquire.call(current);
                });
            }
            var futures = tasks.stream().map(executor::submit).toList();
            ready.await();
            start.countDown();
            List<ConversationGateService.GateDecision> results = new ArrayList<>();
            for (var future : futures) results.add(future.get());
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void clearTestKeys() {
        if (redisTemplate == null) return;
        var keys = redisTemplate.keys("conversation:gate:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    }

    @FunctionalInterface
    private interface IndexedAcquire {
        ConversationGateService.GateDecision call(int index);
    }
}
