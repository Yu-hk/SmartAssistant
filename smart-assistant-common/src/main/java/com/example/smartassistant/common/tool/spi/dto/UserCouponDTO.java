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
import java.time.LocalDateTime;

/**
 * User Coupon DTO — transferred between SPI layer and business modules.
 * <p>Carries coupon information for a specific user.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCouponDTO {

    /** Coupon ID / code */
    private String couponId;

    /** User ID who owns this coupon */
    private Long userId;

    /** Coupon type: FULL_REDUCTION / DISCOUNT / CASH */
    private String type;

    /** Coupon type display name, e.g. 满减券 / 折扣券 / 现金券 */
    private String typeName;

    /** Discount value: full-reduction = reduction amount, discount = rate (0.8), cash = amount */
    private BigDecimal discount;

    /** Minimum order amount required to use this coupon */
    private BigDecimal minAmount;

    /** Whether the coupon has expired */
    private Boolean expired;

    /** Coupon expiration time */
    private LocalDateTime expireAt;

    /** Whether the coupon has been used */
    private Boolean used;
}
