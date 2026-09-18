package com.example.smartassistant.common.error;

import org.junit.jupiter.api.Test;
import com.example.smartassistant.common.gateway.llm.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ModelCallFailureTest {
    @Test void classifyWrappedBillingAndAuthErrorsWithoutLeakingDetails() {
        var failure = ModelCallFailure.from(new RuntimeException(new RuntimeException("402: Insufficient Balance secret-key")));
        assertThat(failure.code()).isEqualTo("MODEL_BILLING_UNAVAILABLE");
        assertThat(failure.getMessage()).doesNotContain("secret", "402");
        assertThat(ModelCallFailure.retryable(new RuntimeException("401 Unauthorized"))).isFalse();
        assertThat(ModelCallFailure.retryable(new RuntimeException("503 unavailable"))).isTrue();
    }
    @Test void gatewayDoesNotRetryInsufficientBalance() {
        var gateway = new AgentLLMGateway();
        var calls = new AtomicInteger();
        gateway.call(() -> { calls.incrementAndGet(); throw new RuntimeException("402: Insufficient Balance"); }, "test", LLMCallConfig.agent());
        assertThat(calls.get()).isEqualTo(1);
    }
}
