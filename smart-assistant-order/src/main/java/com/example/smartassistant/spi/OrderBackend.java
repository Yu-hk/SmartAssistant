/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.spi;

import java.math.BigDecimal;

/**
 * ⭐ 订单数据后端 SPI。
 * <p>
 * 下游集成商可以通过实现此接口替换默认的订单数据源。
 * 框架提供 {@link InMemoryOrderBackend} 作为默认 Mock 实现，
 * 通过 {@code @ConditionalOnMissingBean} 自动注册。
 * </p>
 *
 * <h3>接入方式</h3>
 * <pre>
 * &#64;Component
 * public class MyDatabaseOrderBackend implements OrderBackend {
 *     // Spring 自动检测到此 Bean，默认的 InMemoryOrderBackend 自动让位
 * }
 * </pre>
 */
public interface OrderBackend {

    /** 查询订单状态 */
    String queryOrderStatus(String orderId);

    /** 查询订单金额 */
    String queryOrderAmount(String orderId);

    /** 查询物流信息 */
    String queryLogistics(String orderId);

    /** 创建订单，返回订单号 */
    String createOrder(Long userId, String productName, BigDecimal amount,
                       String contactName, String contactPhone, String shippingAddress);

    /** 处理支付 */
    boolean processPayment(String orderId, String paymentMethod);

    /** 申请退款 */
    String applyRefund(String orderId, String reason, BigDecimal amount);

    /** 取消订单 */
    boolean cancelOrder(String orderId);

    /** 确认收货 */
    boolean confirmDelivery(String orderId);
}
