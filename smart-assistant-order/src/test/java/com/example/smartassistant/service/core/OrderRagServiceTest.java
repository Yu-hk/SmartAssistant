package com.example.smartassistant.service.core;

import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.LogisticsDTO;
import com.example.smartassistant.common.tool.spi.dto.OrderDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderRagServiceTest {

    @Test
    void recentOrderLookup_shouldListOnlyAuthenticatedUsersOrders() {
        OrderDataProvider data = mock(OrderDataProvider.class);
        OrderRagService service = new OrderRagService(data);
        when(data.findRecentOrdersByUserId(7L, 3)).thenReturn(List.of(
                OrderDTO.builder()
                        .orderId("ORD-USER7-003")
                        .userId(7L)
                        .productName("iPhone 15 Pro")
                        .status("已发货")
                        .createdAt(LocalDateTime.of(2026, 8, 2, 9, 30))
                        .build(),
                OrderDTO.builder()
                        .orderId("ORD-USER7-002")
                        .userId(7L)
                        .productName("AirPods Pro")
                        .status("待发货")
                        .createdAt(LocalDateTime.of(2026, 8, 1, 18, 0))
                        .build()));

        RetrievalQualityResult result = service.retrieveWithQualityResult(
                OrderIntentService.IntentType.QUERY_ORDER,
                "查询我的订单物流进度", 7L);

        assertTrue(result.isHighQuality());
        assertTrue(result.getContent().contains("最近的2笔订单"));
        assertTrue(result.getContent().contains("ORD-USER7-003"));
        assertTrue(result.getContent().contains("iPhone 15 Pro"));
        assertTrue(result.getContent().contains("请选择下方要查看的订单"));
        verify(data).findRecentOrdersByUserId(7L, 3);
        verify(data, never()).findOrderByOrderId(anyString());
    }

    @Test
    void recentOrderLookup_shouldReturnFriendlyEmptyState() {
        OrderDataProvider data = mock(OrderDataProvider.class);
        OrderRagService service = new OrderRagService(data);
        when(data.findRecentOrdersByUserId(8L, 3)).thenReturn(List.of());

        RetrievalQualityResult result = service.retrieveWithQualityResult(
                OrderIntentService.IntentType.QUERY_ORDER,
                "查询我的订单物流进度", 8L);

        assertTrue(result.isHighQuality());
        assertTrue(result.getContent().contains("暂未查到订单记录"));
    }

    @Test
    void recentOrderLookup_shouldRequireAuthenticatedUser() {
        OrderDataProvider data = mock(OrderDataProvider.class);
        OrderRagService service = new OrderRagService(data);

        RetrievalQualityResult result = service.retrieveWithQualityResult(
                OrderIntentService.IntentType.QUERY_ORDER,
                "查询我的订单物流进度", null);

        assertTrue(result.isRejected());
        assertTrue(result.getRejectionMessage().contains("请先登录"));
        verify(data, never()).findRecentOrdersByUserId(anyLong(), anyInt());
    }

    @Test
    void secureLookup_shouldRejectCrossAccountOrder() {
        OrderDataProvider data = mock(OrderDataProvider.class);
        OrderRagService service = new OrderRagService(data);
        when(data.findOrderByOrderId("ORD-OWNER-001")).thenReturn(OrderDTO.builder()
                .orderId("ORD-OWNER-001")
                .userId(1L)
                .contactPhone("13000000001")
                .shippingAddress("北京市海淀区中关村测试路1号")
                .status("已发货")
                .build());

        RetrievalQualityResult result = service.retrieveWithQualityResult(
                OrderIntentService.IntentType.QUERY_ORDER,
                "查询订单 ORD-OWNER-001", 2L);

        assertTrue(result.isRejected());
        assertTrue(result.getRejectionMessage().contains("无权访问"));
        assertFalse(result.getRejectionMessage().contains("13000000001"));
        verify(data, never()).findLogisticsByOrderId(anyString());
    }

    @Test
    void secureLookup_shouldOmitUnrequestedCustomerPii() {
        OrderDataProvider data = mock(OrderDataProvider.class);
        OrderRagService service = new OrderRagService(data);
        when(data.findOrderByOrderId("ORD-OWNER-001")).thenReturn(OrderDTO.builder()
                .orderId("ORD-OWNER-001")
                .userId(1L)
                .contactPhone("13000000001")
                .shippingAddress("北京市海淀区中关村测试路1号")
                .status("已发货")
                .build());

        RetrievalQualityResult result = service.retrieveWithQualityResult(
                OrderIntentService.IntentType.QUERY_ORDER,
                "查询订单 ORD-OWNER-001", 1L);

        assertTrue(result.isHighQuality());
        assertTrue(result.getContent().contains("订单号 ORD-OWNER-001"));
        assertFalse(result.getContent().contains("130****0001"));
        assertFalse(result.getContent().contains("13000000001"));
        assertFalse(result.getContent().contains("中关村测试路1号"));
        assertFalse(result.getContent().contains("收货地址："));
        assertFalse(result.getContent().contains("【订单信息】"));
    }

    @Test
    void selectedUnpaidOrder_shouldExplainWhyLogisticsIsUnavailableFromHistoryIntent() {
        OrderDataProvider data = mock(OrderDataProvider.class);
        OrderRagService service = new OrderRagService(data);
        when(data.findOrderByOrderId("ORD-LOAD000001001")).thenReturn(OrderDTO.builder()
                .orderId("ORD-LOAD000001001")
                .userId(1L)
                .productName("测试商品")
                .status("待付款")
                .build());

        String contextualQuestion = "【当前问题】\nORD-LOAD000001001"
                + "\n【历史对话】\n用户：查询我的订单物流进度"
                + "\n助手：请选择一笔订单。";
        RetrievalQualityResult result = service.retrieveWithQualityResult(
                OrderIntentService.IntentType.QUERY_ORDER, contextualQuestion, 1L);

        assertTrue(result.isHighQuality());
        assertTrue(result.getContent().startsWith("我帮您看了一下"));
        assertTrue(result.getContent().contains("还没有进入发货流程"));
        assertTrue(result.getContent().contains("暂时不会有物流信息"));
        assertFalse(result.getContent().contains("收货人："));
        assertFalse(result.getContent().contains("联系电话："));
        assertFalse(result.getContent().contains("收货地址："));
    }

    @Test
    void selectedShippedOrder_shouldLeadWithLogisticsConclusionAndLatestUpdate() {
        OrderDataProvider data = mock(OrderDataProvider.class);
        OrderRagService service = new OrderRagService(data);
        when(data.findOrderByOrderId("ORD-USER7-003")).thenReturn(OrderDTO.builder()
                .orderId("ORD-USER7-003")
                .userId(7L)
                .productName("iPhone 15 Pro")
                .status("已发货")
                .contactName("张三")
                .contactPhone("13000000001")
                .shippingAddress("北京市海淀区中关村测试路1号")
                .build());
        when(data.findLogisticsByOrderId("ORD-USER7-003")).thenReturn(LogisticsDTO.builder()
                .orderId("ORD-USER7-003")
                .companyName("顺丰速运")
                .status("in_transit")
                .logisticsDetail("""
                        [
                          {"time":"2026-08-01 10:00","location":"杭州","desc":"已揽收"},
                          {"time":"2026-08-02 10:00","location":"北京","desc":"运输中"}
                        ]
                        """)
                .build());

        RetrievalQualityResult result = service.retrieveWithQualityResult(
                OrderIntentService.IntentType.QUERY_ORDER,
                "查询订单 ORD-USER7-003 的物流进度", 7L);

        assertTrue(result.isHighQuality());
        assertTrue(result.getContent().contains("已经发货，目前由顺丰速运配送"));
        assertTrue(result.getContent().contains("物流状态是「运输中」"));
        assertTrue(result.getContent().contains("最近一条物流更新：2026-08-02 10:00"));
        assertFalse(result.getContent().contains("2026-08-01 10:00"));
        assertFalse(result.getContent().contains("张三"));
        assertFalse(result.getContent().contains("130****0001"));
        assertFalse(result.getContent().contains("收货地址："));
    }

    @Test
    void explicitRecipientQuestion_shouldReturnOnlyMaskedRecipientInfo() {
        OrderDataProvider data = mock(OrderDataProvider.class);
        OrderRagService service = new OrderRagService(data);
        when(data.findOrderByOrderId("ORD-OWNER-001")).thenReturn(OrderDTO.builder()
                .orderId("ORD-OWNER-001")
                .userId(1L)
                .productName("测试商品")
                .status("待发货")
                .contactName("张三")
                .contactPhone("13000000001")
                .shippingAddress("北京市海淀区中关村测试路1号")
                .build());

        RetrievalQualityResult result = service.retrieveWithQualityResult(
                OrderIntentService.IntentType.QUERY_ORDER,
                "订单 ORD-OWNER-001 的收货地址和联系电话是什么", 1L);

        assertTrue(result.getContent().contains("为保护您的隐私"));
        assertTrue(result.getContent().contains("收货人：张**"));
        assertTrue(result.getContent().contains("联系电话：130****0001"));
        assertTrue(result.getContent().contains("收货地址：北京市海淀区****"));
        assertFalse(result.getContent().contains("中关村测试路1号"));
    }

    @Test
    void compositeAuditQuery_shouldCoverCancellationMarkerAndLatestTwoTrajectories() {
        OrderDataProvider data = mock(OrderDataProvider.class);
        OrderRagService service = new OrderRagService(data);
        OrderDTO order = OrderDTO.builder()
                .orderId("ORD-2024001")
                .status("已发货")
                .paymentMethod("微信支付")
                .carrier("顺丰速运")
                .trackingNo("SF10001")
                .build();
        LogisticsDTO logistics = LogisticsDTO.builder()
                .orderId("ORD-2024001")
                .trackingNo("SF10001")
                .companyName("顺丰速运")
                .status("运输中")
                .logisticsDetail("""
                        [
                          {"time":"2026-07-01 10:00","location":"杭州","desc":"已揽收"},
                          {"time":"2026-07-03 10:00","location":"北京","desc":"运输中"},
                          {"time":"2026-07-02 10:00","location":"上海","desc":"已发出"}
                        ]
                        """)
                .build();
        when(data.findOrderByOrderId("ORD-2024001")).thenReturn(order);
        when(data.findLogisticsByOrderId("ORD-2024001")).thenReturn(logistics);

        String question = "请复核订单 ORD-2024001：列出当前状态、支付方式、物流公司、"
                + "最新两条物流轨迹，并判断是否满足取消条件。核验标记 NC-TRACE-001";
        RetrievalQualityResult result = service.retrieveWithQualityResult(
                OrderIntentService.IntentType.QUERY_ORDER, question);

        assertTrue(result.isHighQuality());
        assertTrue(result.getContent().contains("状态：已发货"));
        assertTrue(result.getContent().contains("支付方式：微信支付"));
        assertTrue(result.getContent().contains("物流公司：顺丰速运"));
        assertTrue(result.getContent().contains("最新2条轨迹"));
        assertTrue(result.getContent().contains("2026-07-03 10:00"));
        assertTrue(result.getContent().contains("2026-07-02 10:00"));
        assertFalse(result.getContent().contains("2026-07-01 10:00"));
        assertTrue(result.getContent().contains("不满足直接取消条件"));
        assertTrue(result.getContent().contains("NC-TRACE-001"));
    }
}
