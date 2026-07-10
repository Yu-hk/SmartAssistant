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

import java.time.LocalDateTime;

/**
 * Logistics DTO — transferred between SPI layer and business modules.
 * <p>Carries courier tracking information for an order.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogisticsDTO {

    /** Primary key */
    private Long id;

    /** Business order ID */
    private String orderId;

    /** Tracking number */
    private String trackingNo;

    /** Logistics company name (carrier) */
    private String companyName;

    /** Logistics status: pending / shipped / in_transit / delivered / returned */
    private String status;

    /** Logistics detail / trajectory description */
    private String logisticsDetail;

    /** Logistics event timestamp */
    private LocalDateTime logisticsTime;

    /** Record creation time */
    private LocalDateTime createdAt;

    /** Record update time */
    private LocalDateTime updatedAt;
}
