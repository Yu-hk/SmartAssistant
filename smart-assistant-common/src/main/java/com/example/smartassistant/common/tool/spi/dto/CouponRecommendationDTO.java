/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool.spi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Coupon Recommendation DTO — transferred between SPI layer and business modules.
 * <p>Carries the best coupon combination recommendation for a given order amount.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CouponRecommendationDTO {

    /** List of recommended coupons for the user */
    private List<UserCouponDTO> recommendedCoupons;

    /** Total discount amount from the recommended coupon combination */
    private BigDecimal totalDiscount;

    /** Original order amount before discount */
    private BigDecimal originalAmount;

    /** Final payable amount after applying discounts */
    private BigDecimal finalAmount;
}
