/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体类 (MyBatis Plus)
 * 映射 orders 表，替代原有内存中 ConcurrentHashMap 模拟数据
 *
 * <p>完整字段列表：
 * <ul>
 *   <li>基础信息：id, order_id, user_id, product_name, amount, status</li>
 *   <li>商品信息：product_type</li>
 *   <li>收货信息：contact_name, contact_phone, shipping_address</li>
 *   <li>物流信息：carrier, tracking_no, delivered_date</li>
 *   <li>支付信息：payment_method</li>
 *   <li>时间信息：created_at, updated_at</li>
 * </ul>
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("orders")
public class OrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private String orderId;

    @TableField("user_id")
    private Long userId;

    @TableField("product_name")
    private String productName;

    @TableField("amount")
    private BigDecimal amount;

    @TableField("status")
    private String status;

    @TableField("carrier")
    private String carrier;

    @TableField("tracking_no")
    private String trackingNo;

    /** 商品类型：电子产品/定制商品/生鲜食品等 */
    @TableField("product_type")
    private String productType;

    /** 签收日期 */
    @TableField("delivered_date")
    private LocalDateTime deliveredDate;

    /** ⭐ 收货人姓名 */
    @TableField("contact_name")
    private String contactName;

    /** ⭐ 收货人电话 */
    @TableField("contact_phone")
    private String contactPhone;

    /** ⭐ 收货地址 */
    @TableField("shipping_address")
    private String shippingAddress;

    /** ⭐ 支付方式，如 微信支付/支付宝/银行卡 */
    @TableField("payment_method")
    private String paymentMethod;

    @TableField("created_at")
    private LocalDateTime createdAt;

    /** ⭐ 最后更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /** ⭐ P2 幂等性：请求 ID，用于数据库层去重 */
    @TableField("request_id")
    private String requestId;
}
