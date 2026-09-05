package com.example.smartassistant.common.gateway.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLLMGatewayTest {

    @Test
    void propagatesRequestContextAcrossRetriesWithoutChangingCaller() {
        var gateway = new AgentLLMGateway();
        var attempts = new AtomicInteger();
        org.slf4j.MDC.put("requestId", "usage-context");
        try {
            var result = gateway.call(() -> {
                assertEquals("usage-context", org.slf4j.MDC.get("requestId"));
                org.slf4j.MDC.put("requestId", "worker-only");
                if (attempts.getAndIncrement() == 0) throw new IllegalStateException("retry");
                return "ok";
            }, "context-test", new LLMCallConfig(null, 128, Duration.ofSeconds(2), 1, 0.1, false));
            assertTrue(result.success());
            assertEquals(2, attempts.get());
            assertEquals("usage-context", org.slf4j.MDC.get("requestId"));
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    @Test
    void timeoutReturnsWithoutWaitingForUncooperativeTaskToFinish() {
        AgentLLMGateway gateway = new AgentLLMGateway();
        LLMCallConfig config = new LLMCallConfig(
                null, 128, Duration.ofMillis(30), 0, 0.1, false);

        long startedAt = System.nanoTime();
        LLMCallResult result = gateway.call(() -> {
            long deadline = System.nanoTime() + Duration.ofMillis(300).toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    // 模拟底层 HTTP 调用未及时响应中断。
                }
            }
            return "too late";
        }, "slow-model", config);
        long wallMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("timeout after 30ms"));
        assertTrue(wallMs < 180, "网关超时后不应等待底层任务自然结束: " + wallMs + "ms");
        assertTrue(result.elapsedMs() >= 20 && result.elapsedMs() < 180);
    }

    @Test
    void circuitBreakerShouldOpenAfterFiveFailedBusinessCalls() {
        AgentLLMGateway gateway = new AgentLLMGateway();
        LLMCallConfig config = new LLMCallConfig(null, 128, Duration.ofSeconds(1), 0, 0.1, true);
        AtomicInteger executions = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            LLMCallResult result = gateway.call(() -> {
                executions.incrementAndGet();
                throw new IllegalStateException("down");
            }, "broken-model", config);
            assertFalse(result.success());
        }

        assertTrue(gateway.isCircuitOpen("broken-model"));
        LLMCallResult rejected = gateway.call(() -> {
            executions.incrementAndGet();
            return "should-not-run";
        }, "broken-model", config);
        assertFalse(rejected.success());
        assertTrue(rejected.errorMessage().contains("circuit_breaker_open"));
        assertEquals(5, executions.get());

        gateway.resetCircuitBreaker("broken-model");
        assertFalse(gateway.isCircuitOpen("broken-model"));
    }
}
