package com.example.smartassistant.service.core;

import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.LogisticsDTO;
import com.example.smartassistant.common.tool.spi.dto.OrderDTO;
import com.example.smartassistant.service.ApprovalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderDeterministicExecutionServiceTest {

    @Mock OrderDataProvider orderData;
    @Mock ApprovalService approvalService;

    private OrderDeterministicExecutionService service;

    @BeforeEach
    void setUp() {
        service = new OrderDeterministicExecutionService(orderData, approvalService);
    }

    @Test
    void queryOrderReturnsVerifiedStructuredResultWithoutModel() {
        when(orderData.findOrderByOrderId("ORD-1001")).thenReturn(order("待付款"));

        AgentExecutionResponse response = service.execute(request(
                "QUERY_ORDER", "查询 ORD-1001 并确认仍为待付款", Map.of("order_id", "ORD-1001")));

        assertThat(response.status()).isEqualTo(AgentExecutionResponse.Status.SUCCEEDED);
        assertThat(response.data()).containsEntry("verified", true)
                .containsEntry("criteriaSatisfied", true)
                .containsEntry("status", "待付款")
                .containsEntry("orderId", "ORD-1001");
        assertThat(response.quality().status()).isEqualTo("PASS");
    }

    @Test
    void queryCurrentStatusDoesNotTreatInterrogativeAlternativesAsExpectedState() {
        when(orderData.findOrderByOrderId("ORD-1001")).thenReturn(order("已发货"));

        AgentExecutionResponse response = service.execute(request(
                "QUERY_ORDER", "查询当前状态，包括是否已支付、是否已发货",
                Map.of("order_id", "ORD-1001")));

        assertThat(response.status()).isEqualTo(AgentExecutionResponse.Status.SUCCEEDED);
        assertThat(response.data()).containsEntry("status", "已发货")
                .containsEntry("criteriaSatisfied", true);
    }

    @Test
    void queryOrderListReturnsOnlyAuthenticatedUsersVerifiedOrders() {
        when(orderData.queryOrdersByUserId(1050L, null, 10, 0)).thenReturn(List.of(
                Map.of("orderId", "ORD-2002", "productName", "Aurora 耳机",
                        "amount", new BigDecimal("1299.00"), "status", "已发货"),
                Map.of("orderId", "ORD-2001", "productName", "Nova 手机",
                        "amount", new BigDecimal("3999.00"), "status", "待付款")));

        AgentExecutionResponse response = service.execute(request(
                "QUERY_ORDER_LIST", "查询我的订单列表", Map.of()));

        assertThat(response.status()).isEqualTo(AgentExecutionResponse.Status.SUCCEEDED);
        assertThat(response.data()).containsEntry("operation", "QUERY_ORDER_LIST")
                .containsEntry("count", 2)
                .containsEntry("verified", true)
                .containsEntry("criteriaSatisfied", true);
        assertThat(response.answer()).contains("ORD-2002", "Aurora 耳机", "已发货",
                "ORD-2001", "Nova 手机", "待付款");
        verify(orderData).queryOrdersByUserId(1050L, null, 10, 0);
        verify(orderData, never()).findOrderByOrderId(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void legacyQueryOrderWithoutOrderIdFallsBackToSuccessfulOrderListQuery() {
        when(orderData.queryOrdersByUserId(1050L, null, 10, 0)).thenReturn(List.of());

        AgentExecutionResponse response = service.execute(request(
                "QUERY_ORDER", "查询我已有的订单列表", Map.of()));

        assertThat(response.status()).isEqualTo(AgentExecutionResponse.Status.SUCCEEDED);
        assertThat(response.answer()).isEqualTo("当前没有查询到你的订单。");
        assertThat(response.data()).containsEntry("operation", "QUERY_ORDER_LIST")
                .containsEntry("count", 0)
                .containsEntry("verified", true);
        verify(orderData).queryOrdersByUserId(1050L, null, 10, 0);
        verify(orderData, never()).findOrderByOrderId(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void orderListCanBeFilteredByStatusAndPaginationIsBounded() {
        when(orderData.queryOrdersByUserId(1050L, "已发货", 20, 2)).thenReturn(List.of());

        AgentExecutionResponse response = service.execute(request(
                "QUERY_ORDER_LIST", "查询我的已发货订单",
                Map.of("status", "已发货", "limit", 100, "offset", 2)));

        assertThat(response.status()).isEqualTo(AgentExecutionResponse.Status.SUCCEEDED);
        assertThat(response.data()).containsEntry("statusFilter", "已发货")
                .containsEntry("limit", 20)
                .containsEntry("offset", 2);
        verify(orderData).queryOrdersByUserId(1050L, "已发货", 20, 2);
    }

    @Test
    void queryPendingPaymentReadsExistingApprovalWithoutCreatingOne() {
        when(orderData.findOrderByOrderId("ORD-1001")).thenReturn(order("待付款"));
        when(approvalService.getPendingApproval("ORD-1001", "payment"))
                .thenReturn(new ApprovalService.PendingApproval(
                        "ORD-1001", "payment", "金额 ¥1299.00", false));

        AgentExecutionResponse response = service.execute(request(
                "QUERY_PAYMENT_PENDING", "查询 ORD-1001 的支付待确认项", Map.of()));

        assertThat(response.status()).isEqualTo(AgentExecutionResponse.Status.SUCCEEDED);
        assertThat(response.data()).containsEntry("pendingApprovalExists", true)
                .containsEntry("approvalStatus", "PENDING")
                .containsEntry("criteriaSatisfied", true);
        assertThat(response.answer()).contains("存在待确认的支付操作");
    }

    @Test
    void queryPendingPaymentTreatsVerifiedAbsenceAsSuccessfulQuery() {
        when(orderData.findOrderByOrderId("ORD-1001")).thenReturn(order("待付款"));
        when(approvalService.getPendingApproval("ORD-1001", "payment")).thenReturn(null);

        AgentExecutionResponse response = service.execute(request(
                "QUERY_PAYMENT_PENDING", "查询 ORD-1001 是否存在支付待确认项", Map.of()));

        assertThat(response.status()).isEqualTo(AgentExecutionResponse.Status.SUCCEEDED);
        assertThat(response.data()).containsEntry("pendingApprovalExists", false)
                .containsEntry("approvalStatus", "NONE")
                .containsEntry("verified", true)
                .containsEntry("criteriaSatisfied", true);
        assertThat(response.answer()).contains("不存在待确认的支付操作");
    }

    @Test
    void rejectsCrossUserOrderRead() {
        when(orderData.findOrderByOrderId("ORD-1001")).thenReturn(order("待付款"));
        AgentExecutionRequest foreign = new AgentExecutionRequest(
                AgentExecutionRequest.CURRENT_VERSION, "req-1", "query", "999",
                "QUERY_ORDER", "查询 ORD-1001", Map.of(), List.of(), List.of(),
                null, null, null);

        AgentExecutionResponse response = service.execute(foreign);

        assertThat(response.status()).isEqualTo(AgentExecutionResponse.Status.FAILED);
        assertThat(response.error().code()).isEqualTo("ORDER_ACCESS_DENIED");
    }

    @Test
    void trackLogisticsSupportsBulkOrderIdAndReturnsVerifiedDomainData() {
        OrderDTO bulkOrder = OrderDTO.builder()
                .orderId("BULK-0002")
                .userId(1050L)
                .productName("iPhone 16 Pro Max")
                .amount(new BigDecimal("9999.00"))
                .status("已发货")
                .build();
        LogisticsDTO logistics = LogisticsDTO.builder()
                .orderId("BULK-0002")
                .trackingNo("SF-BULK-000002")
                .companyName("顺丰速运")
                .status("in_transit")
                .logisticsDetail("[{\"time\":\"2026-08-24 12:00\",\"location\":\"杭州\",\"desc\":\"运输中\"}]")
                .logisticsTime(LocalDateTime.of(2026, 8, 24, 12, 0))
                .build();
        when(orderData.findOrderByOrderId("BULK-0002")).thenReturn(bulkOrder);
        when(orderData.findLogisticsByOrderId("BULK-0002")).thenReturn(logistics);

        AgentExecutionResponse response = service.execute(request(
                "TRACK_LOGISTICS", "查询 BULK-0002 的物流", Map.of()));

        assertThat(response.status()).isEqualTo(AgentExecutionResponse.Status.SUCCEEDED);
        assertThat(response.data()).containsEntry("orderId", "BULK-0002")
                .containsEntry("trackingNo", "SF-BULK-000002")
                .containsEntry("companyName", "顺丰速运")
                .containsEntry("verified", true)
                .containsEntry("criteriaSatisfied", true);
        assertThat(response.answer()).contains("顺丰速运", "SF-BULK-000002", "杭州", "运输中")
                .contains("预计送达时间：暂无可靠数据");
        assertThat(response.quality().status()).isEqualTo("PASS");
    }

    private static AgentExecutionRequest request(String operation, String question,
                                                 Map<String, Object> input) {
        return new AgentExecutionRequest(
                AgentExecutionRequest.CURRENT_VERSION, "req-1", "query", "1050",
                operation, question, input, List.of(), List.of(), null, null, null);
    }

    private static OrderDTO order(String status) {
        return OrderDTO.builder()
                .orderId("ORD-1001")
                .userId(1050L)
                .productName("Aurora 无线降噪耳机")
                .amount(new BigDecimal("1299.00"))
                .status(status)
                .build();
    }
}
