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
 * Order DTO — transferred between SPI layer and business modules.
 * <p>Contains all order fields including MyBatis version for optimistic locking.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDTO {

    /** Primary key (MyBatis) */
    private Long id;

    /** Optimistic lock version (MyBatis) */
    private Integer version;

    /** Business order ID */
    private String orderId;

    /** User ID who placed the order */
    private Long userId;

    /** Product name */
    private String productName;

    /** Order amount */
    private BigDecimal amount;

    /** Order status: 待付款 / 待发货 / 已发货 / 已签收 / 已取消 / 退款中 */
    private String status;

    /** Payment method: 微信支付 / 支付宝 / 银行卡 */
    private String paymentMethod;

    /** Carrier / logistics company */
    private String carrier;

    /** Tracking number */
    private String trackingNo;

    /** Product type: 电子产品 / 定制商品 / 生鲜食品 etc. */
    private String productType;

    /** Delivery date */
    private LocalDateTime deliveredDate;

    /** Recipient contact name */
    private String contactName;

    /** Recipient contact phone */
    private String contactPhone;

    /** Shipping address */
    private String shippingAddress;

    /** Request ID for idempotency */
    private String requestId;

    /** Order creation time */
    private LocalDateTime createdAt;

    /** Last update time */
    private LocalDateTime updatedAt;
}
