package com.example.smartassistant.service.core;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class OrderCheckoutHistoryServiceTest {
    @Test
    void onlyUsesOwnerScopedValidPaidOrdersAndExposesConflictingHistory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LocalDateTime now = LocalDateTime.now();
        var latest = new OrderCheckoutHistoryService.Suggestion(
                "张三", "13800000000", "北京市朝阳区建国路1号", now);
        var older = new OrderCheckoutHistoryService.Suggestion(
                "张三", "13900000000", "北京市海淀区学院路2号", now.minusDays(2));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<OrderCheckoutHistoryService.Suggestion>>any(),
                eq(7L), eq(7L), eq(20))).thenReturn(List.of(latest, latest, older,
                new OrderCheckoutHistoryService.Suggestion("无效", "123", "北京市", now)));
        var service = new OrderCheckoutHistoryService(jdbc);

        var result = service.suggestions(7L);
        assertEquals(List.of(latest, older), result.options());
        assertTrue(result.conflictingHistory());
        assertEquals(List.of("shippingAddress"), service.conflicts(7L,
                Map.of("recipient_name", "张三", "recipient_phone", "13800000000",
                        "shipping_address", "北京市丰台区南三环3号")));
        assertTrue(service.conflicts(7L, Map.of("recipientName", "张三",
                "recipientPhone", "13900000000", "shippingAddress", "北京市海淀区学院路2号")).isEmpty());
        assertEquals(List.of("recipientName", "recipientPhone", "shippingAddress"), service.conflicts(7L,
                Map.of("recipientName", "张三", "recipientPhone", "13800000000",
                        "shippingAddress", "北京市海淀区学院路2号")));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).query(sql.capture(),
                ArgumentMatchers.<RowMapper<OrderCheckoutHistoryService.Suggestion>>any(),
                eq(7L), eq(7L), eq(20));
        assertTrue(sql.getValue().contains("user_id = ?"));
        assertTrue(sql.getValue().contains("analysis_enabled = false"));
    }

    @Test
    void missingOwnerCannotReadHistory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var result = new OrderCheckoutHistoryService(jdbc).suggestions(null);
        assertTrue(result.options().isEmpty());
        assertFalse(result.conflictingHistory());
        verifyNoInteractions(jdbc);
    }
}

