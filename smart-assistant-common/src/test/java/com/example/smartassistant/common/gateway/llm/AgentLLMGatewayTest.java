package com.example.smartassistant.common.gateway.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLLMGatewayTest {

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
}
