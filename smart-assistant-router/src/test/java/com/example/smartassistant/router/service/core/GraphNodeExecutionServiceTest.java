package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.quality.DomainQualityResult;
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
import static org.mockito.ArgumentMatchers.any;
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
    void evidenceLimitedAnswerDoesNotRepeatSameNode() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "products", "查询适合办公室的热门商品", "product", List.of(),
                "列出商品并说明适用场景");
        AgentCallResult limited = new AgentCallResult(
                "当前目录可售候选如下；场景适配证据不足，需进一步核实规格。",
                List.of(), Map.of(),
                DomainQualityResult.pass(1.0, "PRODUCT_SCENARIO_EVIDENCE_LIMITED"));
        when(agentCallerService.callAgentAndExtractTitles(eq("product"), anyString(),
                eq(1L), eq("request"))).thenReturn(limited);

        SubTaskResult result = service.execute(node, Map.of(), new ConcurrentHashMap<>(),
                1L, null, "request");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDomainQuality().isPass()).isTrue();
        verify(agentCallerService, times(1)).callAgentAndExtractTitles(
                eq("product"), anyString(), eq(1L), eq("request"));
        verify(reflectionService, never()).checkCriteria(anyString(), anyString());
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

    @Test
    void passesHotProductStructuredOutputIntoCreateOrderNode() {
        IntentGraph.IntentNode products = new IntentGraph.IntentNode(
                "hot_products", "查询热门商品", "product", List.of(), null, List.of(),
                false, "QUERY_HOT_PRODUCTS", Map.of("limit", 3), List.of(), null);
        IntentGraph.IntentNode order = new IntentGraph.IntentNode(
                "create_order", "选择第一件商品并创建订单", "order", List.of("hot_products"),
                null, List.of(), false, "CREATE_ORDER", Map.of("quantity", 1),
                List.of("必须使用真实 SKU"), "order-scene-001");
        Map<String, Object> productData = Map.of("products", List.of(
                Map.of("sku", "SKU-100", "name", "降噪耳机", "price", 599)));
        when(agentCallerService.callAgentAndExtractTitles(eq("product"), any(AgentExecutionRequest.class)))
                .thenReturn(new AgentCallResult("热门商品：降噪耳机", List.of(), Map.of(),
                        DomainQualityResult.pass(0.95), productData));
        when(agentCallerService.callAgentAndExtractTitles(eq("order"), any(AgentExecutionRequest.class)))
                .thenReturn(new AgentCallResult("订单 ORD-001 创建成功"));

        ConcurrentHashMap<String, Integer> breakers = new ConcurrentHashMap<>();
        SubTaskResult productResult = service.execute(
                products, Map.of(), breakers, 42L, null, "scene-001",
                "shopping", 3, "sha256:shopping-v3");
        SubTaskResult orderResult = service.execute(
                order, Map.of("hot_products", productResult), breakers, 42L, null, "scene-001",
                "shopping", 3, "sha256:shopping-v3");

        ArgumentCaptor<AgentExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(agentCallerService).callAgentAndExtractTitles(eq("order"), requestCaptor.capture());
        AgentExecutionRequest orderRequest = requestCaptor.getValue();
        assertThat(orderResult.isSuccess()).isTrue();
        assertThat(orderResult.getResult()).contains("ORD-001");
        assertThat(orderRequest.predecessorOutputs().get("hot_products").data())
                .isEqualTo(productData);
        assertThat(orderRequest.idempotencyKey()).isEqualTo("order-scene-001");
        assertThat(orderRequest.traceId()).isEqualTo("scene-001");
        assertThat(orderRequest.workflowKey()).isEqualTo("shopping");
        assertThat(orderRequest.workflowVersion()).isEqualTo(3);
        assertThat(orderRequest.workflowChecksum()).isEqualTo("sha256:shopping-v3");
        assertThat(orderRequest.contextRefs()).containsExactly("hot_products");
    }
}
