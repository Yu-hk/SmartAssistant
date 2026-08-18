package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.HandoffCommand;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.checkpoint.LangGraphRedisCheckpointSaver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangGraphRouteExecutionServiceTest {

    private GraphNodeExecutionService nodeExecutor;
    private TaskPlannerService planner;
    private ExecutorService parallelExecutor;
    private LangGraphRouteExecutionService service;

    @BeforeEach
    void setUp() {
        nodeExecutor = mock(GraphNodeExecutionService.class);
        planner = mock(TaskPlannerService.class);
        parallelExecutor = Executors.newFixedThreadPool(4);
        service = new LangGraphRouteExecutionService(nodeExecutor, planner,
                new LangGraphRedisCheckpointSaver(null), parallelExecutor);
        ReflectionTestUtils.setField(service, "maxReplans", 1);
    }

    @AfterEach
    void tearDown() {
        parallelExecutor.shutdownNow();
    }

    @Test
    void executesValidGraphAsNativeNodesAndPreservesResults() {
        IntentGraph graph = graphWithSingleNode();
        SubTaskResult expected = result("task_1", "product_agent", "商品结果");
        when(nodeExecutor.execute(any(), anyMap(), any(), eq(7L), eq("events"), eq("request"),
                any(), any(), any()))
                .thenReturn(expected);

        assertThat(service.execute(graph, 7L, "events", "request"))
                .containsExactly(expected);
        verify(nodeExecutor).execute(any(), anyMap(), any(), eq(7L), eq("events"), eq("request"),
                any(), any(), any());
    }

    @Test
    void rejectsInvalidGraphBeforeCallingAnyAgent() {
        IntentGraph invalid = new IntentGraph("test", List.of(
                new IntentGraph.IntentNode("task_1", "query", "product_agent",
                        List.of("missing_task"))));

        assertThat(service.execute(invalid, 7L, null, "invalid")).isEmpty();
        verify(nodeExecutor, never()).execute(any(), anyMap(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void executesIndependentNodesInParallelAndJoinsBeforeDependentNode() throws Exception {
        IntentGraph.IntentNode first = new IntentGraph.IntentNode("a", "A", "agent_a", List.of());
        IntentGraph.IntentNode second = new IntentGraph.IntentNode("b", "B", "agent_b", List.of());
        IntentGraph.IntentNode joined = new IntentGraph.IntentNode("c", "C", "agent_c", List.of("a", "b"));
        IntentGraph graph = new IntentGraph("parallel", List.of(first, second, joined));
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ConcurrentHashMap<String, Long> times = new ConcurrentHashMap<>();
        when(nodeExecutor.execute(any(), anyMap(), any(), any(), any(), any(),
                any(), any(), any()))
                .thenAnswer(invocation -> {
                    IntentGraph.IntentNode node = invocation.getArgument(0);
                    if (!"c".equals(node.getId())) {
                        entered.countDown();
                        release.await(2, TimeUnit.SECONDS);
                    }
                    times.put(node.getId(), System.nanoTime());
                    return result(node.getId(), node.getTargetAgent(), node.getDescription());
                });

        var future = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> service.execute(graph, 1L, null, "parallel-request"));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        assertThat(future.get(3, TimeUnit.SECONDS)).extracting(SubTaskResult::getTaskId)
                .containsExactly("a", "b", "c");
        assertThat(times.get("c")).isGreaterThan(times.get("a"));
        assertThat(times.get("c")).isGreaterThan(times.get("b"));
    }

    @Test
    void pausesBeforeApprovalNodeAndNeverCallsBusinessAgent() {
        IntentGraph graph = new IntentGraph("pay", List.of(
                new IntentGraph.IntentNode("pay", "支付订单", "order", List.of(),
                        null, List.of(), true)));

        assertThat(service.execute(graph, 1L, null, "approval-request"))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.getAgentName()).isEqualTo("builtin_approval");
                    assertThat(result.getResult()).contains("明确确认");
                });
        verify(nodeExecutor, never()).execute(any(), anyMap(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void restoresGraphFromCheckpointAndResumesThroughAuthenticatedEntryPoint() {
        IntentGraph.IntentNode approval = new IntentGraph.IntentNode(
                "pay", "支付订单", "order", List.of(), null, List.of(), true);
        IntentGraph graph = new IntentGraph("pay", List.of(approval));
        when(nodeExecutor.execute(any(), anyMap(), any(), eq(1L), any(), eq("approved-request"),
                any(), any(), any()))
                .thenAnswer(invocation -> {
                    IntentGraph.IntentNode restored = invocation.getArgument(0);
                    assertThat(restored.getId()).isEqualTo("pay");
                    assertThat(restored.isHumanApprovalRequired()).isTrue();
                    return result("pay", "order", "paid");
                });

        service.execute(graph, 1L, null, "approved-request");
        assertThat(service.resumeApproved(1L, "approved-request"))
                .extracting(SubTaskResult::getTaskId)
                .containsExactly("pay");
        verify(nodeExecutor).execute(any(), anyMap(), any(), eq(1L), any(), eq("approved-request"),
                any(), any(), any());
    }

    @Test
    void refusesApprovalFromAnotherAuthenticatedUser() {
        IntentGraph graph = new IntentGraph("pay", List.of(
                new IntentGraph.IntentNode("pay", "支付订单", "order", List.of(),
                        null, List.of(), true)));

        service.execute(graph, 1L, null, "owned-approval");

        assertThatThrownBy(() -> service.resumeApproved(2L, "owned-approval"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("authenticated user");
        verify(nodeExecutor, never()).execute(any(), anyMap(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void approvalResumeConsumesNativeReplanWithoutInterruptingApprovedNodeAgain() {
        IntentGraph.IntentNode approval = new IntentGraph.IntentNode(
                "write", "创建订单", "order", List.of(), null, List.of(), true);
        IntentGraph.IntentNode replacement = new IntentGraph.IntentNode(
                "write_retry", "修正并创建订单", "order", List.of());
        IntentGraph graph = new IntentGraph("create", List.of(approval));
        when(nodeExecutor.execute(any(), anyMap(), any(), eq(1L), any(), eq("approved-replan"),
                any(), any(), any()))
                .thenAnswer(invocation -> {
                    IntentGraph.IntentNode node = invocation.getArgument(0);
                    if ("write".equals(node.getId())) {
                        return new SubTaskResult("write", "创建订单", "order", "invalid",
                                false, SubTaskResult.ErrorType.NEED_REPLAN);
                    }
                    return result("write_retry", "order", "created");
                });
        when(planner.replan(any()))
                .thenReturn(new IntentGraph("retry", List.of(replacement)));

        service.execute(graph, 1L, null, "approved-replan");

        assertThat(service.resumeApproved(1L, "approved-replan"))
                .extracting(SubTaskResult::getTaskId)
                .containsExactly("write", "write_retry");
        verify(nodeExecutor, times(1)).execute(
                org.mockito.ArgumentMatchers.argThat(node -> "write".equals(node.getId())),
                anyMap(), any(), eq(1L), any(), eq("approved-replan"), any(), any(), any());
    }

    @Test
    void conditionalNodeRunsOnlyWhenNativePredecessorResultMatches() {
        IntentGraph.IntentNode source = new IntentGraph.IntentNode("source", "source", "general", List.of());
        IntentGraph.IntentNode conditional = new IntentGraph.IntentNode(
                "conditional", "conditional", "order", List.of("source"), null,
                List.of(new IntentGraph.IntentNode.ConditionalDependency(
                        "source", IntentGraph.ConditionType.RESULT_SUCCESS, null)), false);
        IntentGraph graph = new IntentGraph("conditional", List.of(source, conditional));
        when(nodeExecutor.execute(eq(source), anyMap(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result("source", "general", "ok"));
        when(nodeExecutor.execute(eq(conditional), anyMap(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result("conditional", "order", "done"));

        assertThat(service.execute(graph, 1L, null, "condition-request"))
                .extracting(SubTaskResult::getTaskId)
                .containsExactly("source", "conditional");
    }

    @Test
    void falseConditionalBranchIsSkippedWithoutBecomingAFailedTask() {
        IntentGraph.IntentNode source = new IntentGraph.IntentNode("source", "source", "general", List.of());
        IntentGraph.IntentNode conditional = new IntentGraph.IntentNode(
                "conditional", "conditional", "order", List.of("source"), null,
                List.of(new IntentGraph.IntentNode.ConditionalDependency(
                        "source", IntentGraph.ConditionType.RESULT_FAILED, null)), false);
        IntentGraph graph = new IntentGraph("conditional", List.of(source, conditional));
        when(nodeExecutor.execute(eq(source), anyMap(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result("source", "general", "ok"));

        assertThat(service.execute(graph, 1L, null, "false-condition"))
                .extracting(SubTaskResult::getTaskId)
                .containsExactly("source");
        verify(nodeExecutor, never()).execute(eq(conditional), anyMap(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void replansInsideNativeLifecycleNodeAndDoesNotReplayCompletedTask() {
        IntentGraph.IntentNode original = new IntentGraph.IntentNode(
                "original", "original", "order", List.of());
        IntentGraph.IntentNode replacement = new IntentGraph.IntentNode(
                "replacement", "replacement", "order", List.of());
        IntentGraph graph = new IntentGraph("replan", List.of(original));
        SubTaskResult needsReplan = new SubTaskResult("original", "original", "order", "bad",
                false, SubTaskResult.ErrorType.NEED_REPLAN);
        when(nodeExecutor.execute(eq(original), anyMap(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(needsReplan);
        when(nodeExecutor.execute(eq(replacement), anyMap(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result("replacement", "order", "recovered"));
        when(planner.replan(any())).thenReturn(new IntentGraph("replacement", List.of(replacement)));

        assertThat(service.execute(graph, 1L, null, "replan-request"))
                .extracting(SubTaskResult::getTaskId)
                .containsExactly("original", "replacement");
        assertThat(needsReplan.getErrorType()).isEqualTo(SubTaskResult.ErrorType.FATAL_FAILED);
        verify(nodeExecutor, times(1)).execute(eq(original), anyMap(), any(), any(), any(), any(),
                any(), any(), any());
        verify(planner, times(1)).replan(any());
    }

    @Test
    void executesExplicitHandoffInNativeHandoffNode() {
        IntentGraph.IntentNode source = new IntentGraph.IntentNode("source", "source", "general", List.of());
        IntentGraph graph = new IntentGraph("handoff", List.of(source));
        SubTaskResult sourceResult = result("source", "general", "need order agent");
        HandoffCommand command = new HandoffCommand(HandoffCommand.HandoffType.HANDOFF,
                "order", "查询订单", "orderId=1");
        sourceResult.setHandoffCommand(command);
        when(nodeExecutor.execute(eq(source), anyMap(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(sourceResult);
        when(nodeExecutor.executeHandoff(eq(command), any(), eq(1L), any(), eq("handoff-request")))
                .thenReturn(result("handoff_order", "order", "done"));

        assertThat(service.execute(graph, 1L, null, "handoff-request"))
                .extracting(SubTaskResult::getTaskId)
                .containsExactly("source", "handoff_order");
        verify(nodeExecutor).executeHandoff(eq(command), any(), eq(1L), any(), eq("handoff-request"));
    }

    @Test
    void followsNativeConditionalBackEdgeWithinIterationBudget() {
        IntentGraph.IntentNode source = new IntentGraph.IntentNode(
                "source", "poll status", "order", List.of());
        IntentGraph.IntentNode marker = new IntentGraph.IntentNode(
                "reroute_marker", "retry while pending", "order", List.of(), null,
                List.of(new IntentGraph.IntentNode.ConditionalDependency(
                        "source", IntentGraph.ConditionType.RESULT_CONTAINS,
                        "pending", "source")), false);
        IntentGraph graph = new IntentGraph("poll", List.of(source, marker), 2);
        when(nodeExecutor.execute(eq(source), anyMap(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result("source", "order", "pending"),
                        result("source", "order", "completed"));

        assertThat(service.execute(graph, 1L, null, "reroute-request"))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.getTaskId()).isEqualTo("source");
                    assertThat(value.getResult()).isEqualTo("completed");
                });
        verify(nodeExecutor, times(2)).execute(eq(source), anyMap(), any(), any(), any(), any(),
                any(), any(), any());
    }

    private static IntentGraph graphWithSingleNode() {
        return new IntentGraph("query", List.of(
                new IntentGraph.IntentNode("task_1", "query product", "product_agent", List.of())));
    }

    private static SubTaskResult result(String id, String agent, String text) {
        return new SubTaskResult(id, text, agent, text, true);
    }
}
