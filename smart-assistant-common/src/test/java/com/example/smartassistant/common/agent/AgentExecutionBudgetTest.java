package com.example.smartassistant.common.agent;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionBudgetTest {
    private final AtomicLong clock = new AtomicLong(123_000_000);
    private final ReActProfile profile = ReActProfile.DEFAULT.withTimeoutMs(10)
            .withContextWindow(100).withTokenBudgetRatio(0.8);
    private AgentExecutionBudget budget(boolean tracked) {
        return new AgentExecutionBudget(profile, tracked, clock::get);
    }

    @Test void accumulatesInputAndOutputAndStopsOnlyAboveLimit() {
        var b = budget(true);
        b.recordTokens(60, 10);
        b.recordTokens(5, 5);
        assertThat(b.maxTokens()).isEqualTo(80);
        assertThat(b.inputTokens()).isEqualTo(65);
        assertThat(b.outputTokens()).isEqualTo(15);
        assertThat(b.tokensExceeded()).isFalse();
        b.recordTokens(0, 1);
        assertThat(b.tokensExceeded()).isTrue();
    }
    @Test void disabledTrackingDoesNotAccumulateOrStop() {
        var b = budget(false);
        b.recordTokens(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertThat(b.inputTokens()).isZero();
        assertThat(b.outputTokens()).isZero();
        assertThat(b.tokensExceeded()).isFalse();
    }
    @Test void totalsUseLongArithmeticAcrossTurns() {
        var b = budget(true);
        b.recordTokens(Integer.MAX_VALUE, Integer.MAX_VALUE);
        b.recordTokens(Integer.MAX_VALUE, 0);
        assertThat(b.inputTokens()).isEqualTo(2L * Integer.MAX_VALUE);
        assertThat(b.tokensExceeded()).isTrue();
    }
    @Test void eachRequestHasIndependentCountersAndStartTime() {
        var first = budget(true);
        first.recordTokens(81, 0);
        clock.addAndGet(20_000_000);
        var second = budget(true);
        assertThat(first.tokensExceeded()).isTrue();
        assertThat(first.elapsedMillis()).isEqualTo(20);
        assertThat(second.tokensExceeded()).isFalse();
        assertThat(second.elapsedMillis()).isZero();
    }
    @Test void exactTimeoutBoundaryStopsNewWork() {
        var b = budget(true);
        clock.addAndGet(9_999_999);
        assertThat(b.remainingNanos()).isEqualTo(1);
        assertThat(b.expired()).isFalse();
        clock.incrementAndGet();
        assertThat(b.elapsedMillis()).isEqualTo(10);
        assertThat(b.timeoutExceeded(b.elapsedMillis())).isTrue();
        assertThat(b.remainingNanos()).isZero();
        assertThat(b.expired()).isTrue();
    }
    @Test void nanoClockWrapRetainsElapsedDuration() {
        clock.set(Long.MAX_VALUE - 5_000_000);
        var b = budget(true);
        clock.addAndGet(11_000_000);
        assertThat(b.elapsedMillis()).isEqualTo(11);
        assertThat(b.timeoutExceeded(b.elapsedMillis())).isTrue();
    }
    @Test void negativeTimeoutStopsBeforeFirstModelCall() {
        var b = new AgentExecutionBudget(profile.withTimeoutMs(-1), true, clock::get);
        assertThat(b.timeoutExceeded(b.elapsedMillis())).isTrue();
    }
    @Test void zeroTokenBudgetAllowsZeroUsageButNotPositiveUsage() {
        var b = new AgentExecutionBudget(profile.withTokenBudgetRatio(0), true, clock::get);
        assertThat(b.tokensExceeded()).isFalse();
        b.recordTokens(1, 0);
        assertThat(b.tokensExceeded()).isTrue();
    }
    @Test void zeroTimeBudgetNeverStartsWork() {
        assertThat(new AgentExecutionBudget(profile.withTimeoutMs(0), true, clock::get).expired()).isTrue();
    }
    @Test void remainingBudgetSaturatesAfterTimeout() {
        var b = budget(true);
        clock.addAndGet(100_000_000);
        assertThat(b.remainingNanos()).isZero();
    }
}
