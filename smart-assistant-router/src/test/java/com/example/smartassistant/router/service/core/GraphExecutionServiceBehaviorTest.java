package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.IntentGraph.IntentNode;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.agent.AgentCallResult;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.heartbeat.AgentHeartbeatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("GraphExecutionService 核心 DAG 执行行为")
class GraphExecutionServiceBehaviorTest {

    private AgentCallerService agentCallerService;
    private ReflectionService reflectionService;
    private ExecutorService parallelExecutor;
    private GraphExecutionService service;

    @BeforeEach
    void setUp() {
        agentCallerService = mock(AgentCallerService.class);
        reflectionService = mock(ReflectionService.class);
        parallelExecutor = Executors.newFixedThreadPool(2);

        when(reflectionService.checkCriteria(anyString(), nullable(String.class)))
                .thenReturn(SubTaskResult.ErrorType.NONE);

        service = new GraphExecutionService(
                agentCallerService,
                parallelExecutor,
                reflectionService,
                mock(TaskPlannerService.class),
                mock(DegradationService.class),
                null,
                null,
                mock(AgentHeartbeatService.class));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        parallelExecutor.shutdownNow();
        assertTrue(parallelExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(10)
    @DisplayName("无依赖节点在同一轮并行执行且结果不丢失")
    void independentNodesExecuteInParallelWithoutLosingResults() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maxActiveCalls = new AtomicInteger();

        when(agentCallerService.callAgentAndExtractTitles(
                anyString(), anyString(), anyLong(), nullable(String.class)))
                .thenAnswer(invocation -> {
                    int active = activeCalls.incrementAndGet();
                    maxActiveCalls.accumulateAndGet(active, Math::max);
                    bothStarted.countDown();
                    if (!bothStarted.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("peer node did not start concurrently");
                    }
                    try {
                        Thread.sleep(50);
                        return new AgentCallResult("result-" + invocation.getArgument(0, String.class));
                    } finally {
                        activeCalls.decrementAndGet();
                    }
                });

        IntentGraph graph = new IntentGraph("parallel question", List.of(
                new IntentNode("weather", "query weather", "weather-agent", List.of()),
                new IntentNode("travel", "query travel", "travel-agent", List.of())));

        List<SubTaskResult> results = service.execute(graph, 7L, null, null);

        assertEquals(2, maxActiveCalls.get(), "同层节点应同时进入 Agent 调用");
        assertEquals(Set.of("weather", "travel"),
                results.stream().map(SubTaskResult::getTaskId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(results.stream().allMatch(SubTaskResult::isSuccess));
    }

    @Test
    @Timeout(10)
    @DisplayName("依赖节点等待上游完成并接收共享上下文")
    void dependentNodeWaitsForParentAndReceivesItsResult() {
        AtomicBoolean parentReturned = new AtomicBoolean(false);
        AtomicBoolean childStartedTooEarly = new AtomicBoolean(false);
        AtomicReference<String> childQuestion = new AtomicReference<>();

        when(agentCallerService.callAgentAndExtractTitles(
                anyString(), anyString(), anyLong(), nullable(String.class)))
                .thenAnswer(invocation -> {
                    String agent = invocation.getArgument(0, String.class);
                    String question = invocation.getArgument(1, String.class);
                    if ("parent-agent".equals(agent)) {
                        parentReturned.set(true);
                        return new AgentCallResult("parent-fact");
                    }
                    childStartedTooEarly.set(!parentReturned.get());
                    childQuestion.set(question);
                    return new AgentCallResult("child-result");
                });

        IntentGraph graph = new IntentGraph("dependent question", List.of(
                new IntentNode("parent", "collect facts", "parent-agent", List.of()),
                new IntentNode("child", "produce answer", "child-agent", List.of("parent"))));

        List<SubTaskResult> results = service.execute(graph, 8L, null, null);

        assertFalse(childStartedTooEarly.get(), "子节点不得早于父节点完成时启动");
        assertNotNull(childQuestion.get());
        assertTrue(childQuestion.get().contains("parent-fact"), "子节点输入应包含上游结果");
        assertEquals(List.of("parent", "child"),
                results.stream().map(SubTaskResult::getTaskId).toList());
    }
}
