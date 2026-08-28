package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.ExecutionPlan;
import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.agent.AgentCallResult;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.agent.AgentMessageDispatcher;
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
    @Mock AgentMessageDispatcher agentMessageDispatcher;

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
    void completedDomainWarningIsDeliveredWithoutGenericSemanticRetry() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "analysis", "分析候选商品", "product", List.of(),
                "按销量、性价比和口碑完成分析");
        AgentCallResult warning = new AgentCallResult(
                "销量持平、口碑证据缺失，当前数据不足以选出唯一商品。",
                List.of(), Map.of(),
                DomainQualityResult.warn(0.4,
                        "PRODUCT_ANALYSIS_FLASH", "UNSUPPORTED_PRODUCT_ANALYSIS_CLAIMS"));
        when(agentCallerService.callAgentAndExtractTitles(eq("product"), anyString(),
                eq(1L), eq("request"))).thenReturn(warning);

        SubTaskResult result = service.execute(node, Map.of(), new ConcurrentHashMap<>(),
                1L, null, "request");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDomainQuality().isWarn()).isTrue();
        assertThat(result.getResult()).contains("数据不足");
        verify(agentCallerService, times(1)).callAgentAndExtractTitles(
                eq("product"), anyString(), eq(1L), eq("request"));
        verify(reflectionService, never()).checkCriteria(anyString(), anyString());
    }

    @Test
    void completedDomainPassIsDeliveredWithoutGenericSemanticRetry() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "recommend", "核实并推荐商品", "product", List.of(),
                "推荐必须与真实候选一致");
        AgentCallResult verified = new AgentCallResult(
                "候选跨品类且缺少偏好，不能可靠选出唯一商品。",
                List.of(), Map.of(),
                DomainQualityResult.pass(1.0, "PRODUCT_RECOMMENDATION_PRO_VERIFIED"));
        when(agentCallerService.callAgentAndExtractTitles(eq("product"), anyString(),
                eq(1L), eq("request"))).thenReturn(verified);

        SubTaskResult result = service.execute(node, Map.of(), new ConcurrentHashMap<>(),
                1L, null, "request");

        assertThat(result.isSuccess()).isTrue();
        verify(agentCallerService, times(1)).callAgentAndExtractTitles(
                eq("product"), anyString(), eq(1L), eq("request"));
        verify(reflectionService, never()).checkCriteria(anyString(), anyString());
    }

    @Test
    void completedDomainFailureIsRejectedWithoutGenericSemanticRetry() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "recommend", "核实并推荐商品", "product", List.of(),
                "推荐必须与真实候选一致");
        AgentCallResult failed = new AgentCallResult(
                "核实未通过。", List.of(), Map.of(),
                DomainQualityResult.fail("PRODUCT_RECOMMENDATION_AUDIT_REJECTED"));
        when(agentCallerService.callAgentAndExtractTitles(eq("product"), anyString(),
                eq(1L), eq("request"))).thenReturn(failed);

        SubTaskResult result = service.execute(node, Map.of(), new ConcurrentHashMap<>(),
                1L, null, "request");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SubTaskResult.ErrorType.FATAL_FAILED);
        verify(agentCallerService, times(1)).callAgentAndExtractTitles(
                eq("product"), anyString(), eq(1L), eq("request"));
        verify(reflectionService, never()).checkCriteria(anyString(), anyString());
    }

    @Test
    void verifiedStructuredResultSkipsLlmCriteriaCheck() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "query_order", "查询订单并确认待付款", "order", List.of(),
                "订单状态为待付款", List.of(), false, "QUERY_ORDER",
                Map.of("order_id", "ORD-1001"), List.of(), null);
        AgentCallResult verified = new AgentCallResult(
                "订单 ORD-1001 状态：待付款", List.of(), Map.of(),
                DomainQualityResult.pass(1.0, "DETERMINISTIC_ORDER_QUERY"),
                Map.of("verified", true, "criteriaSatisfied", true,
                        "orderId", "ORD-1001", "status", "待付款"));
        when(agentCallerService.callAgentAndExtractTitles(eq("order"), any(AgentExecutionRequest.class)))
                .thenReturn(verified);

        SubTaskResult result = service.execute(node, Map.of(), new ConcurrentHashMap<>(),
                1050L, null, "request");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStructuredData()).containsEntry("status", "待付款");
        verify(reflectionService, never()).checkCriteria(anyString(), anyString());
    }

    @Test
    void protocolNodeAlwaysReceivesOriginalUserConstraints() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "recommend", "从候选中选择符合预算和拍照偏好的商品", "product",
                List.of(), null, List.of(), false, "RECOMMEND_PRODUCT",
                Map.of(), List.of("READ_ONLY"), null);
        when(agentCallerService.callAgentAndExtractTitles(
                eq("product"), any(AgentExecutionRequest.class)))
                .thenReturn(new AgentCallResult(
                        "推荐 XIAOMI-15", List.of(), Map.of(),
                        DomainQualityResult.pass(1.0, "PRODUCT_RECOMMENDATION_PRO_VERIFIED")));

        SubTaskResult result = service.execute(node, Map.of(), new ConcurrentHashMap<>(),
                1L, null, "request", null, null, null,
                "预算6000元以内，我重视拍照，不要创建订单");

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<AgentExecutionRequest> request =
                ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(agentCallerService).callAgentAndExtractTitles(eq("product"), request.capture());
        assertThat(request.getValue().question())
                .isEqualTo("预算6000元以内，我重视拍照，不要创建订单");
        assertThat(request.getValue().question())
                .doesNotContain("[用户原始请求]", "[当前节点任务]", "[执行边界]");
        assertThat(request.getValue().input()).containsEntry(
                "_taskDescription", "从候选中选择符合预算和拍照偏好的商品");
    }

    @Test
    void executesBuiltinPreparationWithoutCallingRemoteAgent() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "prepare", "准备订单", RouteExecutionService.BUILTIN_ORDER_PREPARATION_AGENT, List.of());

        SubTaskResult result = service.execute(node, Map.of(), new ConcurrentHashMap<>(),
                1L, null, "request");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAgentName()).isNull();
        assertThat(result.getSystemNodeType())
                .isEqualTo(SubTaskResult.SystemNodeType.ORDER_PREPARATION);
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
    void transportTimeoutResponseDoesNotStartSemanticRetry() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "analysis", "分析商品", "product", List.of(), "输出商品分析");
        AgentCallResult timeout = new AgentCallResult(
                "❌ 调用 Agent 失败: Read timed out", List.of(), Map.of(),
                DomainQualityResult.fail("AGENT_TRANSPORT_TIMEOUT"),
                Map.of(AgentCallResult.TRANSPORT_FAILURE_KEY, true));
        when(agentCallerService.callAgentAndExtractTitles(
                eq("product"), anyString(), eq(1L), eq("request")))
                .thenReturn(timeout);

        SubTaskResult result = service.execute(node, Map.of(), new ConcurrentHashMap<>(),
                1L, null, "request");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SubTaskResult.ErrorType.RETRYABLE_FAILED);
        verify(agentCallerService, times(1)).callAgentAndExtractTitles(
                eq("product"), anyString(), eq(1L), eq("request"));
        verify(reflectionService, never()).checkCriteria(anyString(), anyString());
    }

    @Test
    void neverCallsRemovedGeneralServiceWhenLocalFallbackReturnsEmpty() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "fallback", "回答通用问题", "general_agent", List.of());
        when(fallbackAgentService.execute("回答通用问题", 1L)).thenReturn("");

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

    @Test
    void dispatchesProtocolReadNodeThroughConfiguredMessageTransport() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "hot_products", "查询热门商品", "product", List.of(), null, List.of(),
                false, "QUERY_PRODUCT", Map.of("limit", 3), List.of("仅返回可售商品"),
                null, "READ");
        service.setAgentMessageDispatcher(agentMessageDispatcher);
        when(agentMessageDispatcher.dispatch(eq("product"), any(AgentExecutionRequest.class), eq("READ")))
                .thenReturn(new AgentCallResult("热门商品：降噪耳机"));

        SubTaskResult result = service.execute(
                node, Map.of(), new ConcurrentHashMap<>(), 42L, null, "scene-mq-1",
                "shopping", 5, "sha256:shopping-v5");

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<AgentExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(agentMessageDispatcher).dispatch(eq("product"), requestCaptor.capture(), eq("READ"));
        AgentExecutionRequest queuedRequest = requestCaptor.getValue();
        assertThat(queuedRequest.workflowKey()).isEqualTo("shopping");
        assertThat(queuedRequest.workflowVersion()).isEqualTo(5);
        assertThat(queuedRequest.traceId()).isEqualTo("scene-mq-1");
        verify(agentCallerService, never()).callAgentAndExtractTitles(
                eq("product"), any(AgentExecutionRequest.class));
    }

    @Test
    void resolvesDeclaredStructuredPredecessorBindingsIntoNodeInput() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "recommend", "推荐商品", "product", List.of("analysis"), null, List.of(),
                false, "RECOMMEND_PRODUCT", Map.of("limit", 1), List.of(), null,
                true, ExecutionPlan.MergePolicy.STRUCTURED, "recommendation.v1",
                Map.of("analysisResult", "$.nodes.analysis.data.analysis",
                        "sourceAnswer", "analysis.answer"));
        SubTaskResult predecessor = new SubTaskResult(
                "analysis", "分析商品", "product", "分析完成", true);
        predecessor.setStructuredData(Map.of("analysis", Map.of("bestSku", "SKU-100")));

        Map<String, Object> resolved = GraphNodeExecutionService.resolveInput(
                node, Map.of("analysis", predecessor));

        assertThat(resolved).containsEntry("limit", 1)
                .containsEntry("sourceAnswer", "分析完成");
        assertThat(resolved.get("analysisResult"))
                .isEqualTo(Map.of("bestSku", "SKU-100"));
    }

    @Test
    void resolvesWholeStructuredPredecessorDataBinding() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "analysis", "分析商品", "product", List.of("discover"), null, List.of(),
                false, "ANALYZE_PRODUCT_DATA", Map.of(), List.of(), null,
                true, ExecutionPlan.MergePolicy.STRUCTURED, "analysis.v1",
                Map.of("candidateData", "$.nodes.discover.data"));
        Map<String, Object> productData = Map.of(
                "products", List.of(Map.of("code", "SKU-100", "stock", 12)),
                "productCount", 1);
        SubTaskResult predecessor = new SubTaskResult(
                "discover", "查询商品", "product", "发现 1 个商品", true);
        predecessor.setStructuredData(productData);

        Map<String, Object> resolved = GraphNodeExecutionService.resolveInput(
                node, Map.of("discover", predecessor));

        assertThat(resolved).containsEntry("candidateData", productData);
    }

    @Test
    void derivesLegacyProductIdsBindingFromCanonicalProductCatalog() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "analysis", "分析商品", "product", List.of("discover_products"), null,
                List.of(), false, "ANALYZE_PRODUCT_DATA", Map.of(), List.of(), null,
                true, ExecutionPlan.MergePolicy.STRUCTURED, "analysis.v1",
                Map.of("product_ids", "$.nodes.discover_products.data.product_ids"));
        SubTaskResult predecessor = new SubTaskResult(
                "discover_products", "查询商品", "product", "发现 2 个商品", true);
        predecessor.setStructuredData(Map.of("products", List.of(
                Map.of("code", "AIRPODS-MAX", "name", "AirPods Max"),
                Map.of("code", "IPAD-PRO-M4", "name", "iPad Pro M4"))));

        Map<String, Object> resolved = GraphNodeExecutionService.resolveInput(
                node, Map.of("discover_products", predecessor));

        assertThat(resolved).containsEntry(
                "product_ids", List.of("AIRPODS-MAX", "IPAD-PRO-M4"));
    }

    @Test
    void keepsTrustedExplicitEntityWhenPredecessorBindingUsesMissingFieldAlias() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "query_logistics", "查询物流", "order", List.of("query_order_status"),
                null, List.of(), false, "TRACK_LOGISTICS",
                Map.of("order_id", "BULK-0002"), List.of(), null,
                true, ExecutionPlan.MergePolicy.APPEND, null,
                Map.of("order_id", "$.nodes.query_order_status.data.order_id"));
        SubTaskResult predecessor = new SubTaskResult(
                "query_order_status", "查询订单状态", "order", "订单已发货", true);
        predecessor.setStructuredData(Map.of("orderId", "BULK-0002"));

        Map<String, Object> resolved = GraphNodeExecutionService.resolveInput(
                node, Map.of("query_order_status", predecessor));

        assertThat(resolved).containsEntry("order_id", "BULK-0002");
    }

    @Test
    void rejectsBindingThatBypassesDeclaredDependencies() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "order", "创建订单", "order", List.of("recommend"), null, List.of(),
                false, "CREATE_ORDER", Map.of(), List.of(), "idem-1",
                true, ExecutionPlan.MergePolicy.STRUCTURED, "order.v1",
                Map.of("sku", "$.nodes.inventory.data.sku"));
        SubTaskResult inventory = new SubTaskResult(
                "inventory", "查询库存", "product", "有库存", true);
        inventory.setStructuredData(Map.of("sku", "SKU-100"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        GraphNodeExecutionService.resolveInput(node, Map.of("inventory", inventory)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be resolved");
    }

    @Test
    void exposesFailedPredecessorStatusWithoutTrustingItsPayload() {
        IntentGraph.IntentNode statusConsumer = new IntentGraph.IntentNode(
                "recover", "根据前驱状态恢复", "order", List.of("create_order"), null,
                List.of(), false, "RECOVER_ORDER", Map.of(), List.of(), null,
                true, ExecutionPlan.MergePolicy.STRUCTURED, "recovery.v1",
                Map.of("previousStatus", "$.nodes.create_order.status"));
        SubTaskResult failed = new SubTaskResult(
                "create_order", "创建订单", "order", "不可信的部分响应", false,
                SubTaskResult.ErrorType.FATAL_FAILED);
        failed.setStructuredData(Map.of("orderId", "UNVERIFIED"));

        Map<String, Object> resolved = GraphNodeExecutionService.resolveInput(
                statusConsumer, Map.of("create_order", failed));

        assertThat(resolved).containsEntry("previousStatus", "FATAL_FAILED");
    }

    @Test
    void rejectsUnsupportedBindingSectionAtRuntime() {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "consumer", "消费结果", "product", List.of("source"), null, List.of(),
                false, "QUERY", Map.of(), List.of(), null, true,
                ExecutionPlan.MergePolicy.STRUCTURED, "consumer.v1",
                Map.of("value", "$.nodes.source.payload.value"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        GraphNodeExecutionService.resolveInput(node, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported binding section");
    }
}
