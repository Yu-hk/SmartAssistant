/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tools;

import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.CouponRecommendationDTO;
import com.example.smartassistant.common.tool.spi.dto.UserCouponDTO;
import com.example.smartassistant.order.tool.CouponTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CouponTools}.
 * Tests coupon querying and recommendation functionality via the OrderDataProvider SPI.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CouponTools Unit Tests")
class CouponToolsTest {

    @Mock
    private OrderDataProvider orderData;

    private CouponTools couponTools;

    private UserCouponDTO createFullReductionCoupon(String id, BigDecimal value, BigDecimal condition, LocalDateTime expireAt) {
        return UserCouponDTO.builder()
                .couponId(id)
                .type("FULL_REDUCTION")
                .typeName("满减券")
                .discount(value)
                .minAmount(condition)
                .expireAt(expireAt)
                .build();
    }

    private UserCouponDTO createDiscountCoupon(String id, BigDecimal discountRate, LocalDateTime expireAt) {
        return UserCouponDTO.builder()
                .couponId(id)
                .type("DISCOUNT")
                .typeName("折扣券")
                .discount(discountRate)
                .expireAt(expireAt)
                .build();
    }

    @BeforeEach
    void setUp() {
        couponTools = new CouponTools(orderData);
    }

    // ==================== queryUserCoupons ====================

    @Test
    @DisplayName("queryUserCoupons should return coupon list when coupons exist")
    void should_returnCouponList_when_couponsExist() {
        Long userId = 1L;
        List<UserCouponDTO> coupons = List.of(
                createFullReductionCoupon("1", new BigDecimal("50"), new BigDecimal("200"), LocalDateTime.now().plusDays(30)),
                createDiscountCoupon("2", new BigDecimal("0.8"), LocalDateTime.now().plusDays(15))
        );
        when(orderData.getUserCoupons(userId)).thenReturn(coupons);

        String result = couponTools.queryUserCoupons(userId, 0, 10);

        assertTrue(result.contains("优惠 50元"), "Result should contain the first coupon discount");
        assertTrue(result.contains("满200可用"), "Result should contain the first coupon condition");
        assertTrue(result.contains("折扣券"), "Result should contain the second coupon type");
        verify(orderData).getUserCoupons(userId);
    }

    @Test
    @DisplayName("queryUserCoupons should return empty message when no coupons available")
    void should_returnEmptyMessage_when_noCoupons() {
        Long userId = 1L;
        when(orderData.getUserCoupons(userId)).thenReturn(List.of());

        String result = couponTools.queryUserCoupons(userId, 0, 10);

        assertTrue(result.contains("没有可用的优惠券"), "Should indicate no coupons available");
        verify(orderData).getUserCoupons(userId);
    }

    @Test
    @DisplayName("queryUserCoupons should handle null coupon list gracefully")
    void should_handleNullCouponList_when_backendReturnsNull() {
        Long userId = 1L;
        when(orderData.getUserCoupons(userId)).thenReturn(null);

        String result = couponTools.queryUserCoupons(userId, 0, 10);

        assertTrue(result.contains("没有可用的优惠券"), "Should handle null list gracefully");
        verify(orderData).getUserCoupons(userId);
    }

    @Test
    @DisplayName("queryUserCoupons should handle backend exception gracefully")
    void should_handleBackendException_when_queryFails() {
        Long userId = 1L;
        when(orderData.getUserCoupons(userId)).thenThrow(new RuntimeException("Backend unavailable"));

        String result = couponTools.queryUserCoupons(userId, 0, 10);

        assertTrue(result.contains("查询优惠券失败"), "Should handle exception with error message");
        verify(orderData).getUserCoupons(userId);
    }

    // ==================== findBestCoupon ====================

    @Test
    @DisplayName("findBestCoupon should recommend a full-reduction coupon when it is the best option")
    void should_recommendFullReduction_when_bestForAmount() {
        Long userId = 1L;
        BigDecimal amount = new BigDecimal("500");
        UserCouponDTO fullReduction = createFullReductionCoupon("1", new BigDecimal("50"), new BigDecimal("200"), LocalDateTime.now().plusDays(30));
        List<UserCouponDTO> coupons = List.of(fullReduction);

        CouponRecommendationDTO recommendation = CouponRecommendationDTO.builder()
                .originalAmount(amount)
                .totalDiscount(new BigDecimal("50"))
                .finalAmount(new BigDecimal("450"))
                .build();

        when(orderData.getUserCoupons(userId)).thenReturn(coupons);
        when(orderData.findBestCouponCombination(amount, coupons)).thenReturn(recommendation);

        String result = couponTools.findBestCoupon(userId, amount);

        assertTrue(result.contains("最优优惠方案"), "Result should indicate best coupon found");
        assertTrue(result.contains("450"), "Result should show final price after discount");
        verify(orderData).getUserCoupons(userId);
        verify(orderData).findBestCouponCombination(amount, coupons);
    }

    @Test
    @DisplayName("findBestCoupon should recommend a discount coupon when it is the best option")
    void should_recommendDiscount_when_bestForAmount() {
        Long userId = 1L;
        BigDecimal amount = new BigDecimal("1000");
        UserCouponDTO discount = createDiscountCoupon("1", new BigDecimal("0.8"), LocalDateTime.now().plusDays(10));
        List<UserCouponDTO> coupons = List.of(discount);

        CouponRecommendationDTO recommendation = CouponRecommendationDTO.builder()
                .originalAmount(amount)
                .totalDiscount(new BigDecimal("200"))
                .finalAmount(new BigDecimal("800"))
                .build();

        when(orderData.getUserCoupons(userId)).thenReturn(coupons);
        when(orderData.findBestCouponCombination(amount, coupons)).thenReturn(recommendation);

        String result = couponTools.findBestCoupon(userId, amount);

        assertTrue(result.contains("最优优惠方案"), "Result should indicate best coupon found");
        assertTrue(result.contains("800"), "Result should show final price after discount");
    }

    @Test
    @DisplayName("findBestCoupon should return no suitable coupons message when no coupon matches")
    void should_returnNoSuitableMessage_when_noCouponMatches() {
        Long userId = 1L;
        BigDecimal amount = new BigDecimal("50");
        UserCouponDTO fullReduction = createFullReductionCoupon("1", new BigDecimal("50"), new BigDecimal("200"), LocalDateTime.now().plusDays(30));
        List<UserCouponDTO> coupons = List.of(fullReduction);

        when(orderData.getUserCoupons(userId)).thenReturn(coupons);
        when(orderData.findBestCouponCombination(amount, coupons)).thenReturn(null);

        String result = couponTools.findBestCoupon(userId, amount);

        assertTrue(result.contains("没有满足条件的优惠券"), "Should indicate no suitable coupon");
        verify(orderData).findBestCouponCombination(amount, coupons);
    }

    @Test
    @DisplayName("findBestCoupon should handle no coupons available")
    void should_handleNoCoupons_when_userHasNone() {
        Long userId = 1L;
        BigDecimal amount = new BigDecimal("500");
        when(orderData.getUserCoupons(userId)).thenReturn(List.of());

        String result = couponTools.findBestCoupon(userId, amount);

        assertTrue(result.contains("没有可用的优惠券"), "Should indicate no coupons available");
        verify(orderData, never()).findBestCouponCombination(any(), any());
    }

    @Test
    @DisplayName("findBestCoupon should handle backend exception gracefully")
    void should_handleBackendException_when_bestCouponFails() {
        Long userId = 1L;
        BigDecimal amount = new BigDecimal("500");
        UserCouponDTO coupon = createFullReductionCoupon("1", new BigDecimal("50"), new BigDecimal("200"), LocalDateTime.now().plusDays(30));
        when(orderData.getUserCoupons(userId)).thenReturn(List.of(coupon));
        when(orderData.findBestCouponCombination(amount, List.of(coupon)))
                .thenThrow(new RuntimeException("Calculation failed"));

        String result = couponTools.findBestCoupon(userId, amount);

        assertTrue(result.contains("计算优惠方案失败"), "Should handle exception with error message");
    }
}
