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
 * Refund DTO — transferred between SPI layer and business modules.
 * <p>Carries refund/return request information for an order.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundDTO {

    /** Primary key */
    private Long id;

    /** Business order ID */
    private String orderId;

    /** Refund amount */
    private BigDecimal amount;

    /** Refund reason */
    private String reason;

    /** Refund status: pending / approved / rejected / completed */
    private String status;

    /** Refund request creation time */
    private LocalDateTime createTime;

    /** Record update time */
    private LocalDateTime updatedAt;
}
