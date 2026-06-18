/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ⭐ 默认内存 Mock 订单后端。
 * <p>
 * 当没有其他 {@link OrderBackend} Bean 时自动注册。
 * 使用 ConcurrentHashMap 模拟数据存储，无需数据库即可运行。
 * </p>
 *
 * <p>下游提供真实实现后，此 Bean 自动让位：</p>
 * <pre>
 * &#64;Component
 * public class MyDbBackend implements OrderBackend { ... } // 自动替换此 Mock
 * </pre>
 */
@Component
@ConditionalOnMissingBean(OrderBackend.class)
public class InMemoryOrderBackend implements OrderBackend {

    private static final Logger log = LoggerFactory.getLogger(InMemoryOrderBackend.class);
    private static final AtomicLong ORDER_ID_COUNTER = new AtomicLong(System.currentTimeMillis() % 100000);

    /** 订单存储：orderId → 状态 */
    private final ConcurrentHashMap<String, String> orders = new ConcurrentHashMap<>();
    /** 订单金额存储：orderId → amount */
    private final ConcurrentHashMap<String, BigDecimal> amounts = new ConcurrentHashMap<>();
    /** 订单商品存储 */
    private final ConcurrentHashMap<String, String> products = new ConcurrentHashMap<>();

    @Override
    public String queryOrderStatus(String orderId) {
        String status = orders.get(orderId);
        if (status == null) return null;
        return String.format("订单 %s：%s，金额 ¥%s，商品：%s",
                orderId, status,
                amounts.getOrDefault(orderId, BigDecimal.ZERO).toPlainString(),
                products.getOrDefault(orderId, "未知"));
    }

    @Override
    public String queryOrderAmount(String orderId) {
        BigDecimal amount = amounts.get(orderId);
        if (amount == null) return null;
        return String.format("订单 %s 金额：¥%s", orderId, amount.toPlainString());
    }

    @Override
    public String queryLogistics(String orderId) {
        String status = orders.get(orderId);
        if (status == null) return null;
        if ("已发货".equals(status)) {
            return String.format("订单 %s：已发货，物流公司：默认快递，运单号：SF%s",
                    orderId, orderId.hashCode() & 0x7FFFFFFF);
        }
        return String.format("订单 %s：当前状态「%s」，暂无物流信息", orderId, status);
    }

    @Override
    public String createOrder(Long userId, String productName, BigDecimal amount,
                               String contactName, String contactPhone, String shippingAddress) {
        String orderId = String.format("ORD-%d%04d",
                System.currentTimeMillis() % 1000000,
                ORDER_ID_COUNTER.incrementAndGet() % 10000);
        orders.put(orderId, "待付款");
        amounts.put(orderId, amount);
        products.put(orderId, productName);
        log.info("[MockOrder] 创建订单: orderId={}, product={}, amount={}", orderId, productName, amount);
        return String.format(
                "📦 订单创建成功！\n订单号：%s\n商品：%s\n金额：¥%.2f\n"
                + "收货人：%s\n状态：待付款",
                orderId, productName, amount, contactName);
    }

    @Override
    public boolean processPayment(String orderId, String paymentMethod) {
        String status = orders.get(orderId);
        if (!"待付款".equals(status)) return false;
        orders.put(orderId, "待发货");
        log.info("[MockOrder] 支付成功: orderId={}, method={}", orderId, paymentMethod);
        return true;
    }

    @Override
    public String applyRefund(String orderId, String reason, BigDecimal amount) {
        String status = orders.get(orderId);
        if (status == null) return "未找到订单 " + orderId;
        orders.put(orderId, "退款中");
        log.info("[MockOrder] 申请退款: orderId={}, reason={}, amount={}", orderId, reason, amount);
        return String.format("退款申请已提交：订单 %s，退款金额 ¥%s，原因：%s", orderId, amount.toPlainString(), reason);
    }

    @Override
    public boolean cancelOrder(String orderId) {
        String status = orders.get(orderId);
        if (status == null || "已发货".equals(status) || "已签收".equals(status)) return false;
        orders.put(orderId, "已取消");
        log.info("[MockOrder] 取消订单: orderId={}", orderId);
        return true;
    }

    @Override
    public boolean confirmDelivery(String orderId) {
        String status = orders.get(orderId);
        if (!"已发货".equals(status)) return false;
        orders.put(orderId, "已签收");
        log.info("[MockOrder] 确认收货: orderId={}", orderId);
        return true;
    }
}
