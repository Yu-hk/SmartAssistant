package com.example.smartassistant.common.agent;

import com.example.smartassistant.common.error.ModelCallFailure;
import com.example.smartassistant.common.metrics.AgentMetricsCollector;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import com.example.smartassistant.common.rag.advisor.SummarizationAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Ordered externally observable traces, to run before and after extracting loop components. */
class SmartReActAgentBehaviorBaselineTest {
    private final List<String> events = new ArrayList<>();
    private final ChatModel model = mock(ChatModel.class);
    private final AgentMetricsCollector metrics = new AgentMetricsCollector() {
        @Override public void recordIteration(int n) { events.add("iteration:" + n); }
        @Override public void recordInferenceLatency(long millis) { events.add("inference"); }
        @Override public void recordTokenUsage(int in, int out) { events.add("tokens:" + in + ":" + out); }
        @Override public void recordMaxIterationHit() { events.add("stop"); }
        @Override public void recordTimeout() { events.add("timeout"); }
        @Override public void recordContextCompression() { events.add("compress"); }
    };

    private SmartReActAgent agent() {
        return new SmartReActAgent(model).withMetrics(metrics).withPreALGate(false)
                .withCompress(false, 20, 3).withParallelExecution(false, 1, 30000);
    }

    private ChatResponse response(String text, boolean tool, int input, int output) {
        AssistantMessage message = AssistantMessage.builder().content(text)
                .toolCalls(tool ? List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}")) : List.of()).build();
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(input);
        when(usage.getCompletionTokens()).thenReturn(output);
        when(usage.getTotalTokens()).thenReturn(input + output);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);
        return new ChatResponse(List.of(new Generation(message)), metadata);
    }

    private ToolCallback tool() {
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder().name("lookup")
                .description("Read a fixture").inputSchema("{\"type\":\"object\"}").build());
        when(tool.call("{}")).thenAnswer(call -> { events.add("tool"); return "{\"price\":1999}"; });
        return tool;
    }

    @Test void toolResultPrecedesSecondModelCallAndUsageIsRecordedPerTurn() {
        var first = response("", true, 10, 2);
        var last = response("已查到价格为1999元。", false, 20, 3);
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            if (events.contains("tool")) {
                assertThat(prompt.getInstructions().getLast()).isInstanceOf(ToolResponseMessage.class);
                assertThat(((ToolResponseMessage) prompt.getInstructions().getLast()).getResponses().getFirst().responseData()).contains("1999");
                events.add("model:2");
                return last;
            }
            events.add("model:1");
            return first;
        });
        assertThat(agent().execute("查价格", "sys", List.of(tool()))).isEqualTo("已查到价格为1999元。");
        assertThat(events).containsExactly("iteration:1", "model:1", "inference", "tokens:10:2", "tool",
                "iteration:2", "model:2", "inference", "tokens:20:3");
    }

    @Test void confirmationReturnsFactsAndStopsBeforeToolExecution() {
        String answer = "商品价格1999元。请告诉我是否需要继续下单。";
        var reply = response(answer, true, 10, 2);
        when(model.call(any(Prompt.class))).thenReturn(reply);
        ToolCallback tool = tool();
        assertThat(agent().execute("查商品", "sys", List.of(tool))).isEqualTo(answer);
        verify(tool, never()).call(anyString());
        verify(model).call(any(Prompt.class));
        assertThat(events).containsExactly("iteration:1", "inference", "tokens:10:2", "stop");
    }

    @Test void modelInfrastructureFailureRemainsTypedAndIsNotRetriedAsBusiness() {
        RuntimeException cause = new IllegalStateException("402 fixture balance failure");
        when(model.call(any(Prompt.class))).thenThrow(cause);
        ToolCallback tool = tool();
        assertThatThrownBy(() -> agent().execute("查商品", "sys", List.of(tool)))
                .isInstanceOfSatisfying(ModelCallFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("MODEL_BILLING_UNAVAILABLE");
                    assertThat(failure.getCause()).isSameAs(cause);
                });
        verify(model).call(any(Prompt.class));
        verify(tool, never()).call(anyString());
        assertThat(events).containsExactly("iteration:1");
    }

    @Test void consecutiveEmptyResponsesStopAtTheExistingLimit() {
        when(model.call(any(Prompt.class))).thenReturn(null);
        assertThat(agent().execute("查商品", "sys", List.of())).isNotBlank();
        verify(model, times(3)).call(any(Prompt.class));
        assertThat(events).containsExactly("iteration:1", "inference", "iteration:2", "inference",
                "iteration:3", "inference", "stop");
    }

    @Test void tokenBudgetStopsBeforeAnotherModelCallAfterRecordingConsumedUsage() {
        var reply = response("", true, 90, 11);
        when(model.call(any(Prompt.class))).thenReturn(reply);
        ToolCallback tool = tool();
        assertThat(agent().withTokenBudget(true, 1.0, 100).execute("查商品", "sys", List.of(tool))).isNotBlank();
        verify(model).call(any(Prompt.class));
        verify(tool).call("{}");
        assertThat(events).containsExactly("iteration:1", "inference", "tokens:90:11", "tool");
    }

    @Test void iterationLimitStopsAfterExactlyOneToolCycle() {
        var reply = response("", true, 10, 2);
        when(model.call(any(Prompt.class))).thenReturn(reply);
        ToolCallback tool = tool();
        assertThat(agent().withMaxIterations(1).execute("查商品", "sys", List.of(tool))).isNotBlank();
        verify(model).call(any(Prompt.class));
        verify(tool).call("{}");
        assertThat(events).containsExactly("iteration:1", "inference", "tokens:10:2", "tool", "stop");
    }

    @Test void expiredLoopStopsBeforeModelAndTools() {
        // Negative deadline deterministically exercises the existing elapsed-time boundary.
        ToolCallback tool = tool();
        assertThat(agent().withTimeoutMs(-1).execute("查商品", "sys", List.of(tool))).isNotBlank();
        verifyNoInteractions(model);
        verify(tool, never()).call(anyString());
        assertThat(events).containsExactly("timeout");
    }

    @Test void alreadyCancelledRequestNeverCallsModelOrTools() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> agent().execute("查商品", "sys", List.of()))
                    .isInstanceOf(CancellationException.class);
            verifyNoInteractions(model);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(events).isEmpty();
        } finally { Thread.interrupted(); }
    }

    @Test void cancellationDuringModelCallKeepsConsumedUsageButNeverStartsTool() {
        var reply = response("", true, 10, 2);
        when(model.call(any(Prompt.class))).thenAnswer(i -> {
            Thread.currentThread().interrupt();
            return reply;
        });
        ToolCallback tool = tool();
        try {
            assertThatThrownBy(() -> agent().execute("查商品", "sys", List.of(tool)))
                    .isInstanceOf(CancellationException.class);
            verify(model).call(any(Prompt.class));
            verify(tool, never()).call(anyString());
            assertThat(events).containsExactly("iteration:1", "inference", "tokens:10:2");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void explicitModelCancellationIsNotClassifiedAsModelFailure() {
        CancellationException cancellation = new CancellationException("fixture cancelled");
        when(model.call(any(Prompt.class))).thenThrow(cancellation);
        assertThatThrownBy(() -> agent().execute("查商品", "sys", List.of())).isSameAs(cancellation);
        verify(model).call(any(Prompt.class));
        assertThat(events).containsExactly("iteration:1");
    }

    @Test void synchronousCompressionKeepsToolPairBeforeNextModelCall() {
        SmartReActAgent agent = agent().withCompress(true, 3, 1);
        SummarizationAdvisor compressor = mock(SummarizationAdvisor.class);
        when(compressor.compress(anyList())).thenAnswer(i -> new ArrayList<Message>(i.getArgument(0)));
        ReflectionTestUtils.setField(agent, "contextCompressor", compressor);
        // Block precomputation in this fixture so the synchronous fallback is deterministic.
        ReflectionTestUtils.setField(agent, "precomputedCompactFuture", new CompletableFuture<List<Message>>());
        var first = response("", true, 10, 2);
        var last = response("价格1999元。", false, 20, 3);
        when(model.call(any(Prompt.class))).thenAnswer(i -> {
            if (!events.contains("tool")) { events.add("model:1"); return first; }
            List<Message> messages = ((Prompt) i.getArgument(0)).getInstructions();
            AssistantMessage request = (AssistantMessage) messages.get(messages.size() - 2);
            ToolResponseMessage result = (ToolResponseMessage) messages.getLast();
            assertThat(result.getResponses().getFirst().id()).isEqualTo(request.getToolCalls().getFirst().id());
            assertThat(result.getResponses().getFirst().responseData()).contains("1999");
            events.add("model:2"); return last;
        });
        assertThat(agent.execute("查价格", "sys", List.of(tool()))).isEqualTo("价格1999元。");
        verify(compressor).compress(anyList());
        assertThat(events).containsExactly("iteration:1", "model:1", "inference", "tokens:10:2", "tool",
                "compress", "iteration:2", "model:2", "inference", "tokens:20:3");
    }
}
