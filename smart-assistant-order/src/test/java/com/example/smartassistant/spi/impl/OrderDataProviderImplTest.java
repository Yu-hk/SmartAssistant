package com.example.smartassistant.spi.impl;

import com.example.smartassistant.mapper.CouponMapper;
import com.example.smartassistant.mapper.OrderLogisticsMapper;
import com.example.smartassistant.mapper.OrderMapper;
import com.example.smartassistant.mapper.OrderRefundMapper;
import com.example.smartassistant.service.ApprovalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderDataProviderImplTest {

    @Mock OrderMapper orderMapper;
    @Mock OrderRefundMapper refundMapper;
    @Mock OrderLogisticsMapper logisticsMapper;
    @Mock ApprovalService approvalService;
    @Mock CouponMapper couponMapper;
    @Mock JdbcTemplate jdbcTemplate;

    private OrderDataProviderImpl provider;

    @BeforeEach
    void setUp() {
        provider = new OrderDataProviderImpl(orderMapper, refundMapper, logisticsMapper,
                approvalService, couponMapper, jdbcTemplate);
    }

    @Test
    void queryOrdersByUserIdAlwaysUsesUserBoundaryAndSafeProjection() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        provider.queryOrdersByUserId(42L, null, 10, 0);

        verify(jdbcTemplate).queryForList(sql.capture(), eq(42L), eq(10), eq(0));
        assertThat(sql.getValue())
                .contains("SELECT order_id AS \"orderId\", product_name AS \"productName\"")
                .contains("WHERE user_id = ?")
                .doesNotContain("contact_name", "contact_phone", "shipping_address");
    }

    @Test
    void queryOrdersByUserIdAddsParameterizedStatusFilter() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        provider.queryOrdersByUserId(42L, "已发货", 5, 10);

        verify(jdbcTemplate).queryForList(
                sql.capture(), eq(42L), eq("已发货"), eq(5), eq(10));
        assertThat(sql.getValue())
                .contains("AND status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?");
    }
}
