/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tools;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.idempotent.TaskLogService;
import com.example.smartassistant.common.tool.ReadBeforeEditGuard;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.LogisticsDTO;
import com.example.smartassistant.common.tool.spi.dto.OrderDTO;
import com.example.smartassistant.order.tool.OrderTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderTools}.
 * Covers the full order lifecycle: create, pay, cancel, ship, confirm delivery, refund, and query.
 * All tests use Mockito mocks (OrderDataProvider SPI), no Spring container is started.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderTools Unit Tests")
class OrderToolsTest {

    private static final String S_PENDING_PAY = "待付款";
    private static final String S_PENDING_SHIP = "待发货";
    private static final String S_SHIPPED = "已发货";
    private static final String S_DELIVERED = "已签收";
    private static final String S_CANCELLED = "已取消";
    private static final String S_REFUNDING = "退款中";

    @Mock
    private OrderDataProvider orderData;
    @Mock
    private ReadBeforeEditGuard readGuard;
    @Mock
    private TaskLogService taskLogService;

    private OrderTools orderTools;

    private OrderDTO createOrderDTO(String orderId, String status, BigDecimal amount) {
        return OrderDTO.builder()
                .orderId(orderId)
                .userId(1L)
                .productName("Test Product")
                .amount(amount)
                .status(status)
                .productType("电子产品")
                .contactName("张三")
                .contactPhone("13800138000")
                .shippingAddress("北京市朝阳区")
                .carrier("")
                .trackingNo("")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @BeforeEach
    void setUp() {
        orderTools = new OrderTools(orderData, readGuard, taskLogService);

        // Default: taskLogService.executeIfNotDone executes the supplier
        when(taskLogService.executeIfNotDone(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<String> action = invocation.getArgument(3);
                    return action.get();
                });

        // Default: readGuard returns null (read allowed)
        when(readGuard.requireRead(anyString(), anyString(), anyString())).thenReturn(null);
    }

    // ==================== queryOrder ====================

    @Test
    @DisplayName("queryOrder should return order details when order exists")
    void should_returnOrderDetails_when_queryExistingOrder() {
        String orderId = "ORD-123";
        OrderDTO order = createOrderDTO(orderId, S_PENDING_PAY, new BigDecimal("199.99"));
        when(orderData.findOrderByOrderId(orderId)).thenReturn(order);

        String result = orderTools.queryOrder(orderId);

        assertTrue(result.contains(orderId), "Result should contain order ID");
        assertTrue(result.contains("Test Product"), "Result should contain product name");
        assertTrue(result.contains("199.99"), "Result should contain amount");
        assertTrue(result.contains(S_PENDING_PAY), "Result should contain status");
        verify(orderData).findOrderByOrderId(orderId);
        verify(readGuard).markRead(orderId, "order");
    }

    @Test
    @DisplayName("queryOrder should return error when order does not exist")
    void should_returnError_when_queryNonExistentOrder() {
        String orderId = "ORD-NONEXIST";
        when(orderData.findOrderByOrderId(orderId)).thenReturn(null);

        String result = orderTools.queryOrder(orderId);

        assertTrue(result.contains(AgentErrorCode.ORDER_NOT_FOUND.getCode()),
                "Result should contain ORDER_NOT_FOUND error code");
        verify(orderData).findOrderByOrderId(orderId);
        verify(readGuard, never()).markRead(anyString(), anyString());
    }

    // ==================== trackLogistics ====================

    @Test
    @DisplayName("trackLogistics should return logistics info when tracking number exists")
    void should_returnLogisticsInfo_when_trackingNumberExists() {
        String trackingNo = "SF1234567890";
        LogisticsDTO logistics = LogisticsDTO.builder()
                .trackingNo(trackingNo)
                .orderId("ORD-123")
                .companyName("顺丰速运")
                .status("in_transit")
                .logisticsDetail("[{\"time\":\"2026-07-10 10:00\",\"location\":\"北京\",\"desc\":\"已揽收\"}]")
                .build();
        when(orderData.findLogisticsByTrackingNo(trackingNo)).thenReturn(logistics);

        String result = orderTools.trackLogistics(trackingNo);

        assertTrue(result.contains(trackingNo), "Result should contain tracking number");
        assertTrue(result.contains("顺丰速运"), "Result should contain carrier name");
        verify(orderData).findLogisticsByTrackingNo(trackingNo);
    }

    @Test
    @DisplayName("trackLogistics should return error when tracking number not found")
    void should_returnError_when_trackingNumberNotFound() {
        String trackingNo = "SF0000000000";
        when(orderData.findLogisticsByTrackingNo(trackingNo)).thenReturn(null);

        String result = orderTools.trackLogistics(trackingNo);

        assertTrue(result.contains(AgentErrorCode.LOGISTICS_NOT_FOUND.getCode()),
                "Result should contain LOGISTICS_NOT_FOUND error code");
        verify(orderData).findLogisticsByTrackingNo(trackingNo);
    }

    @Test
    @DisplayName("trackLogistics should return error when tracking number is null or blank")
    void should_returnError_when_trackingNumberIsBlank() {
        String resultNull = orderTools.trackLogistics(null);
        assertTrue(resultNull.contains(AgentErrorCode.TRACKING_REQUIRED.getCode()),
                "Null tracking number should return error");

        String resultEmpty = orderTools.trackLogistics("");
        assertTrue(resultEmpty.contains(AgentErrorCode.TRACKING_REQUIRED.getCode()),
                "Empty tracking number should return error");
    }

    // ==================== createOrder ====================

    @Test
    @DisplayName("createOrder should create order successfully")
    void should_createOrder_when_validParameters() {
        Long userId = 1L;
        String productName = "iPhone 15 Pro";
        BigDecimal amount = new BigDecimal("8999.00");
        String contactName = "张三";
        String contactPhone = "13800138000";
        String shippingAddress = "北京市朝阳区";

        String result = orderTools.createOrder(userId, productName, amount,
                contactName, contactPhone, shippingAddress, null);

        assertTrue(result.contains("订单创建成功"), "Result should indicate success");
        assertTrue(result.contains(productName), "Result should contain product name");
        assertTrue(result.contains("8999.00"), "Result should contain amount");
        verify(orderData).insertOrder(any(OrderDTO.class));
    }

    @Test
    @DisplayName("createOrder should detect duplicate request and return warning")
    void should_returnWarning_when_duplicateCreateRequest() {
        Long userId = 1L;
        String productName = "iPhone 15 Pro";
        BigDecimal amount = new BigDecimal("8999.00");
        String shippingAddress = "北京市朝阳区";

        doThrow(new org.springframework.dao.DuplicateKeyException("Duplicate entry"))
                .when(orderData).insertOrder(any(OrderDTO.class));

        String result = orderTools.createOrder(userId, productName, amount,
                "张三", "13800138000", shippingAddress, null);

        assertTrue(result.contains("检测到重复的订单创建请求"),
                "Should warn about duplicate order creation request");
        verify(orderData).insertOrder(any(OrderDTO.class));
    }

    // ==================== payOrder ====================

    @Test
    @DisplayName("payOrder should create approval when order is pending payment and not yet confirmed")
    void should_createApproval_when_payPendingOrder() {
        String orderId = "ORD-123";
        String paymentMethod = "微信支付";
        OrderDTO order = createOrderDTO(orderId, S_PENDING_PAY, new BigDecimal("199.99"));
        when(orderData.findOrderByOrderId(orderId)).thenReturn(order);
        when(orderData.checkAndConsume(orderId, "payment")).thenReturn(false);

        String result = orderTools.payOrder(orderId, paymentMethod);

        assertTrue(result.contains("确认"), "Result should require user confirmation");
        verify(orderData).createApproval(eq(orderId), eq("payment"), anyString());
        verify(orderData, never()).updatePayment(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("payOrder should execute payment after approval is confirmed")
    void should_executePayment_when_approvalAlreadyConfirmed() {
        String orderId = "ORD-123";
        String paymentMethod = "微信支付";
        OrderDTO order = createOrderDTO(orderId, S_PENDING_PAY, new BigDecimal("199.99"));
        when(orderData.findOrderByOrderId(orderId)).thenReturn(order);
        when(orderData.checkAndConsume(orderId, "payment")).thenReturn(true);

        String result = orderTools.payOrder(orderId, paymentMethod);

        assertTrue(result.contains("支付成功"), "Result should indicate payment success");
        verify(orderData).updatePayment(orderId, S_PENDING_SHIP, paymentMethod);
    }

    @Test
    @DisplayName("payOrder should return error when order status is not pending payment")
    void should_returnError_when_payAlreadyPaidOrder() {
        String orderId = "ORD-123";
        String paymentMethod = "微信支付";
        // Order is already in "已取消" status
        OrderDTO order = createOrderDTO(orderId, S_CANCELLED, new BigDecimal("199.99"));
        when(orderData.findOrderByOrderId(orderId)).thenReturn(order);

        String result = orderTools.payOrder(orderId, paymentMethod);

        assertTrue(result.contains(AgentErrorCode.INVALID_STATUS.getCode()),
                "Should return INVALID_STATUS error for non-pending orders");
    }

    // ==================== cancelOrder ====================

    @Test
    @DisplayName("cancelOrder should cancel a pending payment order")
    void should_cancelOrder_when_orderIsPendingPayment() {
        String orderId = "ORD-123";
        String reason = "不想要了";
        OrderDTO order = createOrderDTO(orderId, S_PENDING_PAY, new BigDecimal("199.99"));
        when(orderData.findOrderByOrderId(orderId)).thenReturn(order);

        String result = orderTools.cancelOrder(orderId, reason);

        assertTrue(result.contains("已取消"), "Result should indicate cancellation");
        assertEquals(S_CANCELLED, order.getStatus(), "Order status should be updated to cancelled");
        verify(orderData).updateOrderById(order);
    }

    @Test
    @DisplayName("cancelOrder should return error when order is already shipped")
    void should_returnError_when_cancelShippedOrder() {
        String orderId = "ORD-123";
        String reason = "不想要了";
        OrderDTO order = createOrderDTO(orderId, S_SHIPPED, new BigDecimal("199.99"));
        when(orderData.findOrderByOrderId(orderId)).thenReturn(order);

        String result = orderTools.cancelOrder(orderId, reason);

        assertTrue(result.contains(AgentErrorCode.INVALID_STATUS.getCode()),
                "Should return INVALID_STATUS error for shipped orders");
        verify(orderData, never()).updateOrderById(any(OrderDTO.class));
    }

    // ==================== applyRefund ====================

    @Test
    @DisplayName("applyRefund should create approval when order is shipped")
    void should_createApproval_when_refundShippedOrder() {
        String orderId = "ORD-123";
        String reason = "商品质量问题";
        OrderDTO order = createOrderDTO(orderId, S_SHIPPED, new BigDecimal("199.99"));
        when(orderData.findOrderByOrderId(orderId)).thenReturn(order);
        when(orderData.checkAndConsume(orderId, "refund")).thenReturn(false);

        String result = orderTools.applyRefund(orderId, reason);

        assertTrue(result.contains("确认"), "Result should require user confirmation");
        verify(orderData).createApproval(orderId, "refund", reason);
    }

    @Test
    @DisplayName("applyRefund should execute refund after approval is confirmed")
    void should_executeRefund_when_approvalAlreadyConfirmed() {
        String orderId = "ORD-123";
        String reason = "商品质量问题";
        OrderDTO order = createOrderDTO(orderId, S_SHIPPED, new BigDecimal("199.99"));
        when(orderData.findOrderByOrderId(orderId)).thenReturn(order);
        when(orderData.checkAndConsume(orderId, "refund")).thenReturn(true);

        String result = orderTools.applyRefund(orderId, reason);

        assertTrue(result.contains("已确认"), "Result should indicate refund confirmed");
        verify(orderData).insertRefund(any());
        verify(orderData).updateStatusByOrderId(orderId, S_REFUNDING);
    }

    @Test
    @DisplayName("applyRefund should return error when order is already refunding")
    void should_returnError_when_orderAlreadyRefunding() {
        String orderId = "ORD-123";
        String reason = "商品质量问题";
        OrderDTO order = createOrderDTO(orderId, S_REFUNDING, new BigDecimal("199.99"));
        when(orderData.findOrderByOrderId(orderId)).thenReturn(order);

        String result = orderTools.applyRefund(orderId, reason);

        assertTrue(result.contains(AgentErrorCode.ALREADY_REFUNDING.getCode()),
                "Should return ALREADY_REFUNDING error");
    }

    // ==================== confirmDelivery ====================

    @Test
    @DisplayName("confirmDelivery should confirm delivery for a shipped order")
    void should_confirmDelivery_when_orderIsShipped() {
        String orderId = "ORD-123";
        OrderDTO order = createOrderDTO(orderId, S_SHIPPED, new BigDecimal("199.99"));
        when(orderData.findOrderByOrderId(orderId)).thenReturn(order);
        // updateOrderById 在 mock 中默认返回 0，会触发源码 "更新影响行数==0 -> UPDATE_FAILED" 分支；
        // 单元测试需桩其返回 >0 以模拟真实 DB 写入成功。
        when(orderData.updateOrderById(any(OrderDTO.class))).thenReturn(1);

        String result = orderTools.confirmDelivery(orderId);

        assertTrue(result.contains("确认收货成功"), "Result should indicate delivery confirmed");
        assertEquals(S_DELIVERED, order.getStatus(), "Order status should be updated to delivered");
        verify(orderData).updateOrderById(order);
    }

    @Test
    @DisplayName("confirmDelivery should return error when order is not shipped")
    void should_returnError_when_confirmDeliveryOnNonShippedOrder() {
        String orderId = "ORD-123";
        OrderDTO order = createOrderDTO(orderId, S_PENDING_PAY, new BigDecimal("199.99"));
        when(orderData.findOrderByOrderId(orderId)).thenReturn(order);

        String result = orderTools.confirmDelivery(orderId);

        assertTrue(result.contains(AgentErrorCode.INVALID_STATUS.getCode()),
                "Should return INVALID_STATUS error for non-shipped orders");
        verify(orderData, never()).updateOrderById(any(OrderDTO.class));
    }

    // ==================== confirmAction ====================

    @Test
    @DisplayName("confirmAction should confirm and return next step instruction")
    void should_confirmAction_when_validOrderIdAndActionType() {
        String orderId = "ORD-123";
        String actionType = "payment";
        when(orderData.confirmAction(orderId, actionType)).thenReturn(true);

        String result = orderTools.confirmAction(orderId, actionType);

        assertTrue(result.contains("确认成功"), "Result should indicate confirmation success");
        assertTrue(result.contains("payOrder"), "Result should hint next call");
        verify(orderData).confirmAction(orderId, actionType);
    }

    @Test
    @DisplayName("confirmAction should return failure when no pending approval exists")
    void should_returnFailure_when_noPendingApproval() {
        String orderId = "ORD-123";
        String actionType = "refund";
        when(orderData.confirmAction(orderId, actionType)).thenReturn(false);

        String result = orderTools.confirmAction(orderId, actionType);

        assertTrue(result.contains("确认失败"), "Result should indicate confirmation failure");
        verify(orderData).confirmAction(orderId, actionType);
    }

    // ==================== Read-before-Edit Guard ====================

    @Test
    @DisplayName("payOrder should be blocked when order has not been read")
    void should_blockPayment_when_orderNotRead() {
        when(readGuard.requireRead(anyString(), eq("order"), eq("queryOrder")))
                .thenReturn("⚠️ 操作被拦截：您尚未查询过该订单的详细信息。");

        String result = orderTools.payOrder("ORD-123", "微信支付");

        assertTrue(result.contains("操作被拦截"), "Should block if order was not read first");
        verify(orderData, never()).findOrderByOrderId(anyString());
    }
}
