package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.*;
import com.example.smartassistant.router.service.agent.*;
import com.example.smartassistant.common.quality.DomainQualityResult;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class ModelUnavailableWorkflowServiceTest {
    @Test void modelSelectedOrderRequirementsDoNotShowCheckoutForRefund() {
        var analysis = TaskAnalysisResult.empty();
        analysis.setIntentCategory("ORDER");
        analysis.setSubIntents(List.of(Map.of("id", "explain", "description", "说明退款要求",
                "target_agent", "order", "operation", "EXPLAIN_ORDER_REQUIREMENTS")));
        var plan = RouteExecutionService.buildExecutionPlan("申请退款 ORD-TEST", analysis, "refund-explain");
        var node = plan.nodes().getFirst();
        assertThat(plan.toIntentGraph().getQuestion()).isEqualTo("申请退款 ORD-TEST");
        assertThat(node.targetAgent()).isEqualTo(RouteExecutionService.BUILTIN_ORDER_PREPARATION_AGENT);
        assertThat(node.operation()).isEqualTo("EXPLAIN_ORDER_REQUIREMENTS");
        assertThat(node.description()).contains("订单执行层").doesNotContain("下单", "收货地址", "成交金额");
        assertThat(node.requiredSlots()).isEmpty();
        assertThat(node.accessMode()).isEqualTo(ExecutionPlan.AccessMode.READ);
    }
    private final AgentCallerService caller = mock(AgentCallerService.class);
    private final RouteExecutionService execution = mock(RouteExecutionService.class);
    private final ProductReadOnlyDispatcher product = mock(ProductReadOnlyDispatcher.class);
    private final ModelUnavailableWorkflowService service = new ModelUnavailableWorkflowService(new BusinessFallbackParser(), product, caller, execution);
    private RouteRequest request(String q) { return new RouteRequest(12L, q, "s", false, "r"); }
    @Test void writeUsesApprovalAndNeverRunsAfterAnExistingExecution() {
        service.handle(request("取消订单 ORD-1001；原因：重复下单"), List.of(), false);
        verifyNoInteractions(execution, caller);
        when(execution.executeDeterministicFallback(any(), eq(12L))).thenAnswer(call -> {
            ExecutionPlan plan = call.getArgument(0);
            assertThat(plan.nodes().getFirst().approvalRequired()).isTrue();
            assertThat(plan.nodes().getFirst().input()).containsEntry("order_id", "ORD-1001");
            assertThat(plan.nodes().getFirst().input()).containsEntry("reason", "重复下单");
            assertThat(plan.nodes().getFirst().description()).contains("原因：重复下单");
            assertThat(plan.nodes().getFirst().idempotencyKey()).isEqualTo("r:fallback-write");
            assertThat(ExecutionPlanValidator.validate(plan).valid()).isTrue();
            return RoutingResult.builder().workflowStatus(RoutingResult.WorkflowStatus.AWAITING_APPROVAL).build();
        });
        assertThat(service.handle(request("取消订单 ORD-1001；原因：重复下单"), List.of(), true).getWorkflowStatus()).isEqualTo(RoutingResult.WorkflowStatus.AWAITING_APPROVAL);
    }
    @Test void missingReasonDoesNotCreateApprovalOrReuseHistoryReason() {
        for (String question : List.of("取消订单 ORD-1001", "申请退款 ORD-1001")) {
            var response = service.handle(request(question), List.of("原因：重复下单"), true);
            assertThat(response.getWorkflowStatus()).isEqualTo(RoutingResult.WorkflowStatus.CLARIFICATION);
            assertThat(response.getResult()).contains("原因");
        }
        verifyNoInteractions(execution, caller, product);
    }
    @Test void refundReasonIsPassedToApprovedOrderNodeWithoutModelCalls() {
        when(execution.executeDeterministicFallback(any(), eq(12L))).thenAnswer(call -> {
            ExecutionPlan plan = call.getArgument(0);
            var node = plan.nodes().getFirst();
            assertThat(node.operation()).isEqualTo("REFUND_ORDER");
            assertThat(node.input()).containsEntry("reason", "商品不合适").containsEntry("_deterministicFallback", true);
            assertThat(node.description()).contains("申请退款 ORD-1001", "原因：商品不合适");
            assertThat(node.approvalRequired()).isTrue();
            return RoutingResult.builder().workflowStatus(RoutingResult.WorkflowStatus.AWAITING_APPROVAL).build();
        });
        assertThat(service.handle(request("申请退款 ORD-1001；原因：商品不合适"), List.of(), true).getWorkflowStatus())
                .isEqualTo(RoutingResult.WorkflowStatus.AWAITING_APPROVAL);
        verifyNoInteractions(caller, product);
    }
    @Test void orderAmountComesFromVerifiedCatalogAndMissingQuoteStops() {
        String q = "下单：AirPods Pro；数量：1；收货人：测试甲；电话：13800138000；地址：北京市测试路一号";
        when(caller.callAgentAndExtractTitles(eq("product"), any(com.example.smartassistant.common.agent.protocol.AgentExecutionRequest.class)))
                .thenReturn(new AgentCallResult("已查询", List.of(), Map.of(), DomainQualityResult.pass(1, "FACT"), Map.of()));
        assertThat(service.handle(request(q), List.of(), true).getResult()).contains("没有创建订单");
        verifyNoInteractions(execution);
        when(caller.callAgentAndExtractTitles(eq("product"), any(com.example.smartassistant.common.agent.protocol.AgentExecutionRequest.class)))
                .thenReturn(new AgentCallResult("已查询", List.of(), Map.of(), DomainQualityResult.pass(1, "FACT"), Map.of("orderQuote", Map.of("productName", "AirPods Pro（第二代）", "amount", 1999))));
        when(execution.executeDeterministicFallback(any(), eq(12L))).thenAnswer(call -> {
            ExecutionPlan plan = call.getArgument(0);
            assertThat(plan.nodes().getFirst().input().get("amount").toString()).isEqualTo("1999");
            assertThat(plan.nodes().getFirst().description()).contains("1999", "1 件");
            assertThat(plan.nodes().getFirst().approvalRequired()).isTrue();
            return RoutingResult.builder().build();
        });
        service.handle(request(q), List.of(), true);
        verify(execution).executeDeterministicFallback(any(), eq(12L));
    }
    @Test void unsupportedAndClarificationDoNotInvokeModelsOrWrites() {
        assertThat(service.handle(request("退单"), List.of(), true).getWorkflowStatus()).isEqualTo(RoutingResult.WorkflowStatus.CLARIFICATION);
        assertThat(service.handle(request("写一首诗"), List.of(), true).getResult()).contains("未能准确处理");
        verifyNoInteractions(execution, caller, product);
    }
}
