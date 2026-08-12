package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.agent.AgentCallResult;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.agent.RouterFallbackAgentService;
import com.example.smartassistant.router.service.heartbeat.AgentHeartbeatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphNodeExecutionServiceTest {

    @Mock AgentCallerService agentCallerService;
    @Mock ReflectionService reflectionService;
    @Mock DegradationService degradationService;
    @Mock AgentHeartbeatService heartbeatService;
    @Mock RouterFallbackAgentService fallbackAgentService;

    private GraphNodeExecutionService service;

    @BeforeEach
    void setUp() {
        service = new GraphNodeExecutionService(agentCallerService, reflectionService,
                degradationService, heartbeatService, fallbackAgentService);
        ReflectionTestUtils.setField(service, "maxCriteriaCorrections", 1);
    }

    @Test
    void performsOneTargetedQualityCorrectionInsideTaskNode() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "task", "查询物流", "order", List.of(), "包含物流单号");
        when(agentCallerService.callAgentAndExtractTitles(eq("order"), anyString(),
                eq(1L), eq("request")))
                .thenReturn(new AgentCallResult("缺少物流"), new AgentCallResult("物流单号 SF001"));
        when(reflectionService.checkCriteria(anyString(), eq("包含物流单号")))
                .thenReturn(SubTaskResult.ErrorType.NEED_REPLAN, SubTaskResult.ErrorType.NONE);

        SubTaskResult result = service.execute(node, Map.of(), new ConcurrentHashMap<>(),
                1L, null, "request");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo("物流单号 SF001");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(agentCallerService, times(2)).callAgentAndExtractTitles(
                eq("order"), prompt.capture(), eq(1L), eq("request"));
        assertThat(prompt.getAllValues().get(1)).contains("缺少物流", "包含物流单号");
    }

    @Test
    void executesBuiltinPreparationWithoutCallingRemoteAgent() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "prepare", "准备订单", RouteExecutionService.BUILTIN_ORDER_PREPARATION_AGENT, List.of());

        SubTaskResult result = service.execute(node, Map.of(), new ConcurrentHashMap<>(),
                1L, null, "request");

        assertThat(result.isSuccess()).isTrue();
        verify(agentCallerService, never()).callAgentAndExtractTitles(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void classifiesOnlyTransientTransportFailuresAsRetryable() {
        assertThat(GraphNodeExecutionService.classifyException(null))
                .isEqualTo(SubTaskResult.ErrorType.FATAL_FAILED);
        assertThat(GraphNodeExecutionService.classifyException(new TimeoutException()))
                .isEqualTo(SubTaskResult.ErrorType.RETRYABLE_FAILED);
        assertThat(GraphNodeExecutionService.classifyException(new SocketTimeoutException()))
                .isEqualTo(SubTaskResult.ErrorType.RETRYABLE_FAILED);
        assertThat(GraphNodeExecutionService.classifyException(new IOException("connection reset")))
                .isEqualTo(SubTaskResult.ErrorType.RETRYABLE_FAILED);
        assertThat(GraphNodeExecutionService.classifyException(new IOException("file not found")))
                .isEqualTo(SubTaskResult.ErrorType.FATAL_FAILED);
        assertThat(GraphNodeExecutionService.classifyException(
                new IllegalStateException("wrapped", new SocketTimeoutException())))
                .isEqualTo(SubTaskResult.ErrorType.RETRYABLE_FAILED);
        assertThat(GraphNodeExecutionService.classifyException(new IllegalArgumentException("bad input")))
                .isEqualTo(SubTaskResult.ErrorType.FATAL_FAILED);
    }

    @Test
    void neverCallsRemovedGeneralServiceWhenLocalFallbackReturnsEmpty() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "fallback", "回答通用问题", "general_agent", List.of());
        when(fallbackAgentService.execute("回答通用问题", 1L, null)).thenReturn("");

        SubTaskResult result = service.execute(node, Map.of(), new ConcurrentHashMap<>(),
                1L, null, "request");

        assertThat(result.isSuccess()).isFalse();
        verify(agentCallerService, never()).callAgentAndExtractTitles(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
