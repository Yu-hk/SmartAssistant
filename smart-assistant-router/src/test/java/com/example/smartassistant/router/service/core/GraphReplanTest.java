package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.agent.AgentCallResult;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.heartbeat.AgentHeartbeatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphReplanTest {

    @Mock AgentCallerService agentCallerService;
    @Mock ReflectionService reflectionService;
    @Mock TaskPlannerService taskPlannerService;
    @Mock DegradationService degradationService;
    @Mock AgentHeartbeatService heartbeatService;

    GraphExecutionService service;

    @BeforeEach
    void setUp() {
        service = new GraphExecutionService(agentCallerService, Runnable::run,
                reflectionService, taskPlannerService, degradationService,
                null, null, heartbeatService);
    }

    private static IntentGraph graph() {
        return new IntentGraph("原始问题", List.of(
                new IntentGraph.IntentNode("task1", "查询订单", "order",
                        List.of(), "必须返回订单状态和物流单号")));
    }

    private static SubTaskResult failed() {
        return new SubTaskResult("task1", "查询订单", "order", "缺少物流单号",
                false, SubTaskResult.ErrorType.NEED_REPLAN);
    }

    @Test
    void replanPromptUsesOriginalSuccessCriteria() {
        when(taskPlannerService.replan(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new IntentGraph("replan", List.of()));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        List<?> nodes = ReflectionTestUtils.invokeMethod(service,
                "replanFailedNode", graph(), failed(), List.of(failed()));

        assertTrue(nodes != null && nodes.isEmpty());
        verify(taskPlannerService).replan(prompt.capture());
        assertTrue(prompt.getValue().contains("验收标准：必须返回订单状态和物流单号"));
    }

    @Test
    void exhaustedBudgetPreventsFurtherReplanning() {
        ConcurrentHashMap<String, SubTaskResult> completed = new ConcurrentHashMap<>();
        completed.put("task1", failed());

        Integer count = ReflectionTestUtils.invokeMethod(service,
                "triggerReplanIfNeeded", graph(), completed, List.of(failed()), 0);

        assertEquals(0, count);
        verify(taskPlannerService, never()).replan(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void criteriaFailureGetsOneTargetedCorrectionThenReturnsBestEffort() {
        ReflectionTestUtils.setField(service, "maxCriteriaCorrections", 1);
        when(agentCallerService.callAgentAndExtractTitles(
                org.mockito.ArgumentMatchers.eq("order"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("req-1")))
                .thenReturn(new AgentCallResult("缺少物流单号"),
                        new AgentCallResult("订单已发货，但当前数据未提供物流单号"));
        when(reflectionService.checkCriteria(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("必须返回订单状态和物流单号")))
                .thenReturn(SubTaskResult.ErrorType.NEED_REPLAN);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        SubTaskResult result = ReflectionTestUtils.invokeMethod(service, "executeNode",
                graph().getAllNodes().iterator().next(),
                new ConcurrentHashMap<String, SubTaskResult>(),
                new ConcurrentHashMap<String, Integer>(),
                1L, null, "req-1");

        assertTrue(result != null && result.isSuccess());
        assertEquals("订单已发货，但当前数据未提供物流单号", result.getResult());
        verify(agentCallerService, times(2)).callAgentAndExtractTitles(
                org.mockito.ArgumentMatchers.eq("order"), prompt.capture(),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("req-1"));
        assertTrue(prompt.getAllValues().get(1).contains("必须返回订单状态和物流单号"));
        assertTrue(prompt.getAllValues().get(1).contains("缺少物流单号"));
        verify(taskPlannerService, never()).replan(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void successfulReplanIsProcessedOnlyOnceAndKeepsOriginalCompleted() {
        IntentGraph original = graph();
        IntentGraph replanned = new IntentGraph("replan", List.of(
                new IntentGraph.IntentNode("task1_r1", "补充物流信息", "order",
                        List.of(), "返回物流信息")));
        when(taskPlannerService.replan(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(replanned);
        SubTaskResult failed = failed();
        ConcurrentHashMap<String, SubTaskResult> completed = new ConcurrentHashMap<>();
        completed.put("task1", failed);

        Integer first = ReflectionTestUtils.invokeMethod(service,
                "triggerReplanIfNeeded", original, completed, List.of(failed), 1);
        Integer second = ReflectionTestUtils.invokeMethod(service,
                "triggerReplanIfNeeded", original, completed, List.of(failed), 1);

        assertEquals(1, first);
        assertEquals(0, second);
        assertTrue(completed.containsKey("task1"));
        assertEquals(2, original.getNodeCount());
        verify(taskPlannerService, times(1)).replan(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void duplicateReplanNodeDoesNotConsumeBudget() {
        IntentGraph original = graph();
        when(taskPlannerService.replan(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new IntentGraph("replan", List.of(
                        new IntentGraph.IntentNode("task1", "重复节点", "order",
                                List.of(), "重复"))));
        SubTaskResult failed = failed();
        ConcurrentHashMap<String, SubTaskResult> completed = new ConcurrentHashMap<>();
        completed.put("task1", failed);

        Integer count = ReflectionTestUtils.invokeMethod(service,
                "triggerReplanIfNeeded", original, completed, List.of(failed), 1);

        assertEquals(0, count);
        assertEquals(1, original.getNodeCount());
        assertTrue(completed.containsKey("task1"));
    }
}
