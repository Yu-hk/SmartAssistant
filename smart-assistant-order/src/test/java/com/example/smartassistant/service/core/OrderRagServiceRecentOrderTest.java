package com.example.smartassistant.service.core;

import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.OrderDTO;
import com.example.smartassistant.service.core.OrderIntentService.IntentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderRagServiceRecentOrderTest {

    @Test
    void recentOrderQueryUsesAuthenticatedUsersLatestOrder() {
        OrderDataProvider provider = mock(OrderDataProvider.class);
        OrderDTO order = OrderDTO.builder()
                .orderId("ORD-E2E-0001")
                .userId(6L)
                .status("已发货")
                .build();
        when(provider.findRecentOrdersByUserId(6L, 1)).thenReturn(List.of(order));
        when(provider.findOrderByOrderId("ORD-E2E-0001")).thenReturn(order);

        RetrievalQualityResult result = new OrderRagService(provider)
                .retrieveWithQualityResult(IntentType.QUERY_ORDER,
                        "帮我查询最近一笔订单的物流状态", 6L);

        assertFalse(result.isRejected());
        assertTrue(result.getContent().contains("ORD-E2E-0001"));
        assertTrue(result.getContent().contains("暂无物流记录"));
        verify(provider).findRecentOrdersByUserId(6L, 1);
    }

    @Test
    void explicitOrderIdFromAnotherUserIsNotDisclosed() {
        OrderDataProvider provider = mock(OrderDataProvider.class);
        OrderDTO order = OrderDTO.builder()
                .orderId("ORD-OTHER-0001")
                .userId(99L)
                .status("已发货")
                .build();
        when(provider.findOrderByOrderId("ORD-OTHER-0001")).thenReturn(order);

        RetrievalQualityResult result = new OrderRagService(provider)
                .retrieveWithQualityResult(IntentType.QUERY_ORDER,
                        "查询 ORD-OTHER-0001", 6L);

        assertTrue(result.isRejected());
        assertTrue(result.getRejectionMessage().contains("未找到"));
        verify(provider, never()).findLogisticsByOrderId("ORD-OTHER-0001");
    }
}
