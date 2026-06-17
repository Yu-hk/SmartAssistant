/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.controller;

import com.example.smartassistant.tools.OrderTools;
import com.example.smartassistant.tools.TextToSqlTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * 订单工具测试控制器
 * <p>
 * 用于在未部署 Router 服务时直接测试 OrderTools 各个工具方法。
 * 每个接口对应一个 @Tool 方法，返回工具执行的原始结果字符串。
 * ⚠️ 仅用于开发测试，生产环境应移除。
 * </p>
 */
@RestController
@RequestMapping("/api/test/order")
public class OrderToolTestController {

    private static final Logger log = LoggerFactory.getLogger(OrderToolTestController.class);

    private final OrderTools orderTools;
    private final TextToSqlTool textToSqlTool;

    public OrderToolTestController(OrderTools orderTools, TextToSqlTool textToSqlTool) {
        this.orderTools = orderTools;
        this.textToSqlTool = textToSqlTool;
    }

    /** 1. 下单 */
    @PostMapping("/create")
    public String createOrder(
            @RequestParam Long userId,
            @RequestParam String productName,
            @RequestParam BigDecimal amount,
            @RequestParam String contactName,
            @RequestParam String contactPhone,
            @RequestParam String shippingAddress,
            @RequestParam(required = false) String productType) {
        log.info("[TestAPI] 下单: userId={}, productName={}", userId, productName);
        return orderTools.createOrder(userId, productName, amount, contactName, contactPhone, shippingAddress, productType);
    }

    /** 2. 支付（首次调用 — 二次确认） */
    @PostMapping("/pay")
    public String payOrder(
            @RequestParam String orderId,
            @RequestParam String paymentMethod) {
        log.info("[TestAPI] 支付: orderId={}, paymentMethod={}", orderId, paymentMethod);
        return orderTools.payOrder(orderId, paymentMethod);
    }

    /** 3. 确认操作（支付/退款确认） */
    @PostMapping("/confirm")
    public String confirmAction(
            @RequestParam String orderId,
            @RequestParam String actionType) {
        log.info("[TestAPI] 确认: orderId={}, actionType={}", orderId, actionType);
        return orderTools.confirmAction(orderId, actionType);
    }

    /** 4. 取消订单 */
    @PostMapping("/cancel")
    public String cancelOrder(
            @RequestParam String orderId,
            @RequestParam String reason) {
        log.info("[TestAPI] 取消: orderId={}, reason={}", orderId, reason);
        return orderTools.cancelOrder(orderId, reason);
    }

    /** 5. 发货 */
    @PostMapping("/ship")
    public String shipOrder(
            @RequestParam String orderId,
            @RequestParam String carrier,
            @RequestParam String trackingNo) {
        log.info("[TestAPI] 发货: orderId={}, carrier={}", orderId, carrier);
        return orderTools.shipOrder(orderId, carrier, trackingNo);
    }

    /** 6. 确认收货 */
    @PostMapping("/deliver")
    public String confirmDelivery(
            @RequestParam String orderId) {
        log.info("[TestAPI] 确认收货: orderId={}", orderId);
        return orderTools.confirmDelivery(orderId);
    }

    /** 7. 查询订单 */
    @GetMapping("/query")
    public String queryOrder(
            @RequestParam String orderId) {
        log.info("[TestAPI] 查询订单: orderId={}", orderId);
        return orderTools.queryOrder(orderId);
    }

    /** 8. 申请退款（首次调用 — 二次确认） */
    @PostMapping("/refund")
    public String applyRefund(
            @RequestParam String orderId,
            @RequestParam String reason) {
        log.info("[TestAPI] 退款: orderId={}", orderId);
        return orderTools.applyRefund(orderId, reason);
    }

    /** 9. 物流查询 */
    @GetMapping("/track")
    public String trackLogistics(
            @RequestParam String trackingNumber) {
        log.info("[TestAPI] 物流查询: trackingNo={}", trackingNumber);
        return orderTools.trackLogistics(trackingNumber);
    }

    /** 10. Text-to-SQL */
    @PostMapping("/text2sql")
    public String textToSql(
            @RequestParam String question) {
        log.info("[TestAPI] Text-to-SQL: question={}", question);
        return textToSqlTool.textToSql(question);
    }

    /** 11. 全流程一键测试 */
    @PostMapping("/flow/full")
    public String fullFlowTest() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 全流程一键测试 ==========\n\n");

        try {
            // Step 1: 创建订单
            sb.append("【1/5】下单...\n");
            String r1 = orderTools.createOrder(1L, "测试商品-全流程", new BigDecimal("999.00"),
                    "测试用户", "13800000000", "北京市测试地址", "电子产品");
            sb.append(r1).append("\n\n");
            String orderId = extractOrderId(r1);

            if (orderId == null) {
                return sb.append("❌ 无法从返回中提取订单号").toString();
            }

            // Step 2: 支付（首次 → 确认 → 执行）
            sb.append("【2/5】支付（二次确认）...\n");
            sb.append("  首次调用(创建确认项):\n");
            String r2a = orderTools.payOrder(orderId, "微信支付");
            sb.append("  ").append(r2a).append("\n");
            sb.append("  确认支付:\n");
            String r2b = orderTools.confirmAction(orderId, "payment");
            sb.append("  ").append(r2b).append("\n");
            sb.append("  再次调用(执行支付):\n");
            String r2c = orderTools.payOrder(orderId, "微信支付");
            sb.append("  ").append(r2c).append("\n\n");

            // Step 3: 发货
            sb.append("【3/5】发货...\n");
            String r3 = orderTools.shipOrder(orderId, "顺丰速运", "SF-FLOW-TEST-001");
            sb.append(r3).append("\n\n");

            // Step 4: 确认收货
            sb.append("【4/5】确认收货...\n");
            String r4 = orderTools.confirmDelivery(orderId);
            sb.append(r4).append("\n\n");

            // Step 5: 查询最终状态
            sb.append("【5/5】查询最终状态...\n");
            String r5 = orderTools.queryOrder(orderId);
            sb.append(r5).append("\n");

            sb.append("\n========== 全流程测试完成 ✅ ==========\n");
        } catch (Exception e) {
            sb.append("\n❌ 测试异常: ").append(e.getMessage()).append("\n");
            log.error("[TestAPI] 全流程测试异常", e);
        }

        return sb.toString();
    }

    /** 从下单返回中提取订单号 */
    private String extractOrderId(String response) {
        if (response == null) return null;
        for (String line : response.split("\n")) {
            if (line.contains("订单号：")) {
                String id = line.replace("订单号：", "").trim();
                if (id.startsWith("ORD-")) return id;
            }
        }
        return null;
    }
}
