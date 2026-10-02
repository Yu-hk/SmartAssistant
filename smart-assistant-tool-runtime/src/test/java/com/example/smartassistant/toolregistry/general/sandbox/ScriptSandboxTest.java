package com.example.smartassistant.toolregistry.general.sandbox;

import com.example.smartassistant.common.error.AgentErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScriptSandboxTest {

    @Test
    void evaluatesBoundedMathScript() {
        ScriptSandbox sandbox = new ScriptSandbox(new ScriptSandboxProperties());
        try {
            var result = sandbox.execute("a = 3\nb = 4\nc = sqrt(a^2+b^2)");
            assertTrue(result.success());
            assertTrue(result.output().contains("c = 5"));
        } finally {
            sandbox.shutdown();
        }
    }

    @Test
    void rejectsDangerousAndOversizedScripts() {
        ScriptSandbox sandbox = new ScriptSandbox(new ScriptSandboxProperties());
        try {
            var dangerous = sandbox.execute("y = system(1)");
            assertFalse(dangerous.success());
            assertEquals(AgentErrorCode.SECURITY_SCRIPT_REJECTED, dangerous.errorCode());

            var oversized = sandbox.execute("1+".repeat(1001));
            assertFalse(oversized.success());
            assertEquals(AgentErrorCode.SECURITY_SCRIPT_RESOURCE_LIMIT, oversized.errorCode());
        } finally {
            sandbox.shutdown();
        }
    }

    @Test
    void disabledSandboxUsesInlineExecution() {
        ScriptSandboxProperties properties = new ScriptSandboxProperties();
        properties.setEnabled(false);
        ScriptSandbox sandbox = new ScriptSandbox(properties);
        try {
            assertTrue(sandbox.execute("x = 2 * 3").output().contains("= 6"));
        } finally {
            sandbox.shutdown();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \n ", "# comment only", "unknown", "x=unknown", "x="})
    void noValidComputationIsNotSuccess(String script) {
        withSandbox(new ScriptSandboxProperties(), sandbox -> {
            var result = sandbox.execute(script);
            assertFalse(result.success());
            assertNull(result.output());
            assertEquals(AgentErrorCode.VALIDATION_SCRIPT_EMPTY, result.errorCode());
            assertNotNull(result.hint());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMPORT x", "Runtime(1)", "new x", "1; x=2", "socket(1)"})
    void dangerousInputsAreRejectedCaseInsensitively(String script) {
        withSandbox(new ScriptSandboxProperties(), sandbox ->
                assertEquals(AgentErrorCode.SECURITY_SCRIPT_REJECTED, sandbox.execute(script).errorCode()));
    }

    @Test
    void understandsEscapedNewlinesCommentsBlankLinesAndDependentVariables() {
        withSandbox(new ScriptSandboxProperties(), sandbox -> {
            var result = sandbox.execute("# synthetic calculation\\n\\na=3\\nb=a*2\\nb+1");
            assertTrue(result.success());
            assertTrue(result.output().contains("synthetic calculation"));
            assertTrue(result.output().contains("b = 6"));
            assertTrue(result.output().contains("b+1 = 7"));
            assertTrue(result.output().endsWith("b = 6"));
            assertNull(result.errorCode());
        });
    }

    @Test
    void scriptLengthBoundaryIsInclusiveEvenWhenIsolationIsDisabled() {
        var props = new ScriptSandboxProperties();
        props.setEnabled(false);
        props.setMaxScriptLength(3);
        withSandbox(props, sandbox -> {
            assertTrue(sandbox.execute("1+2").success());
            var tooLong = sandbox.execute("1+22");
            assertEquals(AgentErrorCode.SECURITY_SCRIPT_RESOURCE_LIMIT, tooLong.errorCode());
            assertTrue(tooLong.message().contains("4"));
        });
    }

    @Test
    void limitsLinesAfterDecodingEscapedNewlines() {
        var props = new ScriptSandboxProperties();
        props.setMaxLines(2);
        withSandbox(props, sandbox -> {
            assertTrue(sandbox.execute("1\\n2").success());
            assertEquals(AgentErrorCode.SECURITY_SCRIPT_RESOURCE_LIMIT,
                    sandbox.execute("1\\n2\\n3").errorCode());
        });
    }

    @Test
    void variableLimitAllowsReassignmentButRejectsAnotherVariable() {
        var props = new ScriptSandboxProperties();
        props.setMaxVariables(1);
        withSandbox(props, sandbox -> {
            var reassigned = sandbox.execute("x=2\nx=x+3");
            assertTrue(reassigned.success());
            assertTrue(reassigned.output().endsWith("x = 5"));
            assertEquals(AgentErrorCode.SECURITY_SCRIPT_RESOURCE_LIMIT,
                    sandbox.execute("x=2\ny=3").errorCode());
        });
    }

    @Test
    void skipsOversizedExpressionWithoutEvaluatingItAndContinuesValidLines() {
        var props = new ScriptSandboxProperties();
        props.setMaxExpressionLength(3);
        withSandbox(props, sandbox -> {
            var partial = sandbox.execute("1+22\n2+3");
            assertTrue(partial.success());
            assertTrue(partial.output().contains("表达式过长，已跳过"));
            assertTrue(partial.output().contains("2+3 = 5"));
            assertFalse(partial.output().contains("= 23"));
            assertFalse(sandbox.execute("1+22").success());
        });
    }

    @Test
    void longSkippedExpressionIsAbbreviated() {
        var props = new ScriptSandboxProperties();
        props.setMaxExpressionLength(3);
        withSandbox(props, sandbox -> {
            var result = sandbox.execute("1+".repeat(30) + "1\n2+3");
            assertTrue(result.success());
            assertTrue(result.output().contains("1+".repeat(20) + "..."));
        });
    }

    @Test
    void failedAssignmentDoesNotEraseLastValidValueAndFailureRemainsVisible() {
        withSandbox(new ScriptSandboxProperties(), sandbox -> {
            var result = sandbox.execute("x=2\nx=unknown\ny=x+3\nunknown");
            assertTrue(result.success());
            assertTrue(result.output().contains("计算失败"));
            assertTrue(result.output().contains("无法解析"));
            assertTrue(result.output().endsWith("y = 5"));
        });
    }

    @Test
    void outputTruncationIsExplicitAndStopsLaterSteps() {
        var props = new ScriptSandboxProperties();
        props.setMaxOutputLength(15);
        withSandbox(props, sandbox -> {
            var result = sandbox.execute("x=1\ny=2\nz=3");
            assertTrue(result.success());
            assertTrue(result.output().contains("输出已截断"));
            assertTrue(result.output().endsWith("x = 1"));
            assertFalse(result.output().contains("y = 2"));
            // Existing contract checks the cap before each line, not a strict final byte/character cap.
        });
    }

    @Test
    void formatsFractionalNonFiniteAndPureExpressionResults() {
        withSandbox(new ScriptSandboxProperties(), sandbox -> {
            var result = sandbox.execute("1/3\nexp(1000)\nsqrt(-1)");
            assertTrue(result.success());
            assertTrue(result.output().contains("0.333333"));
            assertTrue(result.output().contains("结果无穷大"));
            assertTrue(result.output().contains("结果不是有效数字"));
            assertFalse(result.output().contains("最终结果"));
        });
    }

    @Test
    void parallelExecutionsCannotReadEachOthersVariables() throws Exception {
        var sandbox = new ScriptSandbox(new ScriptSandboxProperties());
        try (var callers = Executors.newFixedThreadPool(4)) {
            var results = new ArrayList<Future<ScriptSandbox.SandboxResult>>();
            for (int i = 1; i <= 12; i++) {
                int value = i;
                results.add(callers.submit(() -> sandbox.execute("x=" + value + "\ny=x*2")));
            }
            for (int i = 0; i < results.size(); i++) {
                var result = results.get(i).get(5, TimeUnit.SECONDS);
                assertTrue(result.success());
                assertTrue(result.output().endsWith("y = " + (2 * (i + 1))));
            }
            assertFalse(sandbox.execute("y=x+1").success());
        } finally {
            sandbox.shutdown();
        }
    }

    @Test
    void timeoutCancelsWorkAndReturnsSecurityError() throws Exception {
        var props = new ScriptSandboxProperties();
        props.setTimeoutMs(321);
        withControlledFuture(props, future -> {
            when(future.get(321, TimeUnit.MILLISECONDS)).thenThrow(new TimeoutException());
        }, sandbox -> {
            var result = sandbox.execute("1+2");
            assertEquals(AgentErrorCode.SECURITY_SCRIPT_TIMEOUT, result.errorCode());
            assertTrue(result.message().contains("321ms"));
        }, future -> verify(future).cancel(true));
    }

    @Test
    void executionFailureReturnsRootMessageWithoutSuccessOutput() throws Exception {
        withControlledFuture(new ScriptSandboxProperties(), future ->
                when(future.get(2000, TimeUnit.MILLISECONDS)).thenThrow(
                        new ExecutionException(new IllegalStateException("synthetic failure"))), sandbox -> {
            var result = sandbox.execute("1+2");
            assertEquals(AgentErrorCode.TOOL_EXECUTION_ERROR, result.errorCode());
            assertNull(result.output());
            assertTrue(result.message().contains("synthetic failure"));
        }, future -> verify(future, never()).cancel(anyBoolean()));
    }

    @Test
    void interruptionRestoresCallerInterruptFlag() throws Exception {
        try {
            withControlledFuture(new ScriptSandboxProperties(), future ->
                    when(future.get(2000, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException()), sandbox -> {
                var result = sandbox.execute("1+2");
                assertEquals(AgentErrorCode.TOOL_EXECUTION_ERROR, result.errorCode());
                assertTrue(Thread.currentThread().isInterrupted());
            }, future -> {});
        } finally {
            Thread.interrupted();
        }
    }

    private static void withSandbox(ScriptSandboxProperties props, java.util.function.Consumer<ScriptSandbox> action) {
        var sandbox = new ScriptSandbox(props);
        try {
            action.accept(sandbox);
        } finally {
            sandbox.shutdown();
        }
    }

    // Deterministic exception-branch checks, not process termination or real wall-clock timeout evidence.
    @SuppressWarnings("unchecked")
    private static void withControlledFuture(ScriptSandboxProperties props,
                                            ThrowingConsumer<Future<ScriptSandbox.SandboxResult>> arrange,
                                            java.util.function.Consumer<ScriptSandbox> action,
                                            java.util.function.Consumer<Future<ScriptSandbox.SandboxResult>> verify) throws Exception {
        var sandbox = new ScriptSandbox(props);
        sandbox.shutdown(); // close the original, unused executor before replacing it
        var executor = mock(ExecutorService.class);
        Future<ScriptSandbox.SandboxResult> future = mock(Future.class);
        when(executor.submit(any(Callable.class))).thenReturn(future);
        ReflectionTestUtils.setField(sandbox, "sandboxExecutor", executor);
        try {
            arrange.accept(future);
            action.accept(sandbox);
            verify.accept(future);
        } finally {
            sandbox.shutdown();
            verify(executor).shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
