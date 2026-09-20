package com.example.smartassistant.common.agent;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Request-local accounting; the caller owns stop responses, metrics and cancellation.
 * Checked at loop boundaries, not a deadline that interrupts an in-flight model call.
 */
final class AgentExecutionBudget {
    private final LongSupplier nanoClock;
    private final long startedAt;
    private final long timeoutMs;
    private final boolean trackTokens;
    private final long maxTokens;
    private long inputTokens;
    private long outputTokens;

    AgentExecutionBudget(ReActProfile profile, boolean trackTokens) {
        this(profile, trackTokens, System::nanoTime);
    }

    AgentExecutionBudget(ReActProfile profile, boolean trackTokens, LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.startedAt = nanoClock.getAsLong();
        this.timeoutMs = profile.timeoutMs();
        this.trackTokens = trackTokens;
        this.maxTokens = (long) (profile.contextWindow() * profile.tokenBudgetRatio());
    }

    long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - startedAt);
    }

    boolean timeoutExceeded(long elapsedMillis) { return elapsedMillis > timeoutMs; }
    boolean tokensExceeded() { return trackTokens && inputTokens + outputTokens > maxTokens; }
    void recordTokens(int input, int output) {
        if (trackTokens) {
            inputTokens += input;
            outputTokens += output;
        }
    }
    long inputTokens() { return inputTokens; }
    long outputTokens() { return outputTokens; }
    long maxTokens() { return maxTokens; }
}
