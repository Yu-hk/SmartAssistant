package com.example.smartassistant.common.agent;

import com.example.smartassistant.common.error.ErrorRecoveryService;
import com.example.smartassistant.common.metrics.AgentMetricsCollector;
import com.example.smartassistant.common.observability.OpsMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** All callbacks are fixtures; no real order or remote service is invoked. */
@Timeout(10)
class AgentToolExecutorConcurrencyTest {
    private AgentToolExecutor executor(int concurrency, long timeout) {
        return new AgentToolExecutor(ReActProfile.DEFAULT.withMaxConcurrency(concurrency)
                .withToolTimeoutMs(timeout), new AgentMetricsCollector() {},
                ErrorRecoveryService.DEFAULT, new OpsMetrics(), true);
    }

    private AssistantMessage.ToolCall call(String name) {
        return new AssistantMessage.ToolCall("id-" + name, "function", name, "{}");
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test void resultsKeepCallOrderEvenWhenSecondCallbackFinishesFirst() throws Exception {
        CountDownLatch secondDone = new CountDownLatch(1);
        ToolCallback first = mock(ToolCallback.class), second = mock(ToolCallback.class);
        when(first.call("{}")).thenAnswer(i -> { await(secondDone); return "first-result"; });
        when(second.call("{}")).thenAnswer(i -> { secondDone.countDown(); return "second-result"; });
        var results = executor(2, 5000).execute(List.of(call("first"), call("second")),
                Map.of("first", first, "second", second));
        assertThat(results).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("id-first", "id-second");
        assertThat(results).extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly("first-result", "second-result");
        verify(first).call("{}"); verify(second).call("{}");
    }

    @Test void interruptedBatchNeverReplaysCallbacksAndPreservesInterrupt() throws Exception {
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.call("{}")).thenAnswer(i -> {
            calls.incrementAndGet(); started.countDown();
            // Simulate an external call that cannot be rolled back by a Java interrupt.
            boolean done = false;
            while (!done) {
                try { done = release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* release in test finally */ }
                if (!done && release.getCount() != 0) return "fixture-timeout";
            }
            return "accepted";
        });
        Thread caller = Thread.ofPlatform().start(() -> {
            try { executor(2, 5000).execute(List.of(call("a"), call("b")), Map.of("a", tool, "b", tool)); }
            catch (Throwable e) { failure.set(e); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            await(started); caller.interrupt(); caller.join(500);
        } finally {
            release.countDown(); caller.join(3000);
        }
        assertThat(caller.isAlive()).isFalse();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(failure.get()).isInstanceOf(CancellationException.class);
        assertThat(interrupted.get()).isTrue();
    }

    @Test void exceptionalFutureDoesNotReplaySuccessfulSibling() {
        ToolCallback good = mock(ToolCallback.class), failed = mock(ToolCallback.class);
        when(good.call("{}")).thenReturn("committed-fixture");
        when(failed.call("{}")).thenThrow(new AssertionError("fixture executor failure"));
        var results = executor(2, 5000).execute(List.of(call("good"), call("failed")),
                Map.of("good", good, "failed", failed));
        verify(good).call("{}"); verify(failed).call("{}");
        assertThat(results.get(0).responseData()).isEqualTo("committed-fixture");
        assertThat(results.get(1).id()).isEqualTo("id-failed");
        assertThat(results.get(1).name()).isEqualTo("failed");
        assertThat(results.get(1).responseData()).contains("UNCONFIRMED", "\"retryable\":false")
                .doesNotContain("fixture executor failure");
    }

    @Test void timedOutQueuedCallsKeepIdentityAndAreNotStartedAfterReturn() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.call("{}")).thenAnswer(i -> {
            calls.incrementAndGet(); started.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { /* cancellable fixture */ }
            finally { exited.countDown(); }
            return "result";
        });
        List<ToolResponseMessage.ToolResponse> results;
        try {
            results = executor(1, 150).execute(List.of(call("a"), call("b")), Map.of("a", tool, "b", tool));
            await(started);
        } finally { release.countDown(); }
        await(exited);
        assertThat(results).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("id-a", "id-b");
        assertThat(results).extracting(ToolResponseMessage.ToolResponse::name).containsExactly("a", "b");
        assertThat(results).allSatisfy(r -> assertThat(r.responseData()).contains("TOOL_TIMEOUT", "\"retryable\":false"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void alreadyInterruptedCallerDoesNotStartEvenOneCallback() {
        ToolCallback tool = mock(ToolCallback.class);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> executor(2, 5000).execute(List.of(call("one")), Map.of("one", tool)))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(tool);
        } finally { Thread.interrupted(); }
    }

    @Test void completedSiblingSurvivesAnotherToolsTimeout() throws Exception {
        CountDownLatch finished = new CountDownLatch(1), release = new CountDownLatch(1);
        ToolCallback slow = mock(ToolCallback.class), fast = mock(ToolCallback.class);
        when(slow.call("{}")).thenAnswer(i -> { release.await(3, TimeUnit.SECONDS); return "slow"; });
        when(fast.call("{}")).thenAnswer(i -> { finished.countDown(); return "confirmed"; });
        try {
            var result = executor(2, 1000).execute(List.of(call("slow"), call("fast")), Map.of("slow", slow, "fast", fast));
            await(finished);
            assertThat(result.get(0).responseData()).contains("TOOL_TIMEOUT");
            assertThat(result.get(1).responseData()).isEqualTo("confirmed");
            assertThat(result.get(1).id()).isEqualTo("id-fast");
            verify(fast).call("{}");
        } finally { release.countDown(); }
    }

    @Test void batchStopPreventsRetryWhenCallbackSwallowsInterrupt() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        ToolCallback slow = mock(ToolCallback.class), fast = mock(ToolCallback.class);
        when(fast.call("{}")).thenReturn("confirmed");
        when(slow.call("{}")).thenAnswer(i -> {
            attempts.incrementAndGet();
            try { new CountDownLatch(1).await(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { /* upstream library clears interrupt */ }
            finally { finished.countDown(); }
            return "{\"error_code\":\"RAG_EMBEDDING_UNAVAILABLE\"}";
        });
        var result = executor(2, 150).execute(List.of(call("slow"), call("fast")), Map.of("slow", slow, "fast", fast));
        await(finished);
        assertThat(result.get(0).responseData()).contains("TOOL_TIMEOUT");
        assertThat(attempts.get()).isEqualTo(1);
    }
}
