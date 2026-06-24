/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tools;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.tool.ReadBeforeEditGuard;
import com.example.smartassistant.common.tool.ToolResult;
import com.example.smartassistant.entity.OrderEntity;
import com.example.smartassistant.entity.OrderLogisticsEntity;
import com.example.smartassistant.entity.OrderRefundEntity;
import com.example.smartassistant.mapper.OrderLogisticsMapper;
import com.example.smartassistant.mapper.OrderMapper;
import com.example.smartassistant.mapper.OrderRefundMapper;
import com.example.smartassistant.service.ApprovalService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单客服工具集（数据库持久化版）
 * <p>
 * 覆盖完整订单生命周期：
 * <pre>
 *   下单 → 支付 → 发货 → 确认收货
 *    │                 │
 *    └──→ 取消 ←───────┘
 *                         │
 *   已发货/已签收 → 退款申请 → 退款中
 * </pre>
 * </p>
 */
@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final AtomicLong ORDER_ID_COUNTER = new AtomicLong(System.currentTimeMillis() % 100000);

    /** 状态常量 */
    private static final String S_PENDING_PAY = "待付款";
    private static final String S_PENDING_SHIP = "待发货";
    private static final String S_SHIPPED = "已发货";
    private static final String S_DELIVERED = "已签收";
    private static final String S_CANCELLED = "已取消";
    private static final String S_REFUNDING = "退款中";

    private final ApprovalService approvalService;
    private final OrderMapper orderMapper;
    private final OrderRefundMapper orderRefundMapper;
    private final OrderLogisticsMapper orderLogisticsMapper;
    private final ReadBeforeEditGuard readGuard;

    public OrderTools(ApprovalService approvalService,
                      OrderMapper orderMapper,
                      OrderRefundMapper orderRefundMapper,
                      OrderLogisticsMapper orderLogisticsMapper,
                      ReadBeforeEditGuard readGuard) {
        this.approvalService = approvalService;
        this.orderMapper = orderMapper;
        this.orderRefundMapper = orderRefundMapper;
        this.orderLogisticsMapper = orderLogisticsMapper;
        this.readGuard = readGuard;
    }

    // ==================== 下单 ====================

    @Transactional
    @Tool(description = "【下单】创建新的订单。用户提供商品名称、金额和收货信息，系统自动生成订单号。"
            + "下单成功后状态为「待付款」，后续可调用 payOrder 完成支付。")
    public String createOrder(
            @ToolParam(description = "用户ID", required = true) Long userId,
            @ToolParam(description = "商品名称，如 iPhone 15 Pro 256GB", required = true) String productName,
            @ToolParam(description = "商品金额，如 8999.00", required = true) BigDecimal amount,
            @ToolParam(description = "收货人姓名", required = true) String contactName,
            @ToolParam(description = "收货人电话", required = true) String contactPhone,
            @ToolParam(description = "收货地址", required = true) String shippingAddress,
            @ToolParam(description = "商品类型，如 电子产品/定制商品/生鲜食品，留空则自动识别", required = false) String productType) {
        log.info("[OrderTool] 创建订单: userId={}, productName={}, amount={}", userId, productName, amount);

        String orderId = String.format("ORD-%d%04d",
                System.currentTimeMillis() % 1000000,
                ORDER_ID_COUNTER.incrementAndGet() % 10000);

        String type = (productType != null && !productType.isBlank()) ? productType : inferProductType(productName);

        OrderEntity order = OrderEntity.builder()
                .orderId(orderId)
                .userId(userId)
                .productName(productName)
                .amount(amount)
                .status(S_PENDING_PAY)
                .carrier("")
                .trackingNo("")
                .productType(type)
                .contactName(contactName)
                .contactPhone(contactPhone)
                .shippingAddress(shippingAddress)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        orderMapper.insert(order);
        log.info("[OrderTool] ✅ 订单创建成功: orderId={}", orderId);

        return String.format(
                "📦 订单创建成功！\n订单号：%s\n商品：%s\n金额：¥%.2f\n商品类型：%s\n"
                + "收货人：%s\n联系电话：%s\n收货地址：%s\n"
                + "状态：待付款\n\n"
                + "下一步：请提醒用户核对收货信息并完成付款，可调用 payOrder(orderId=\"%s\") 进行支付。",
                orderId, productName, amount, type,
                contactName, contactPhone, shippingAddress,
                orderId);
    }

    // ==================== 支付 ====================

    @Transactional
    @Tool(description = "【支付】完成订单支付。⚠️ 资金敏感操作，需二次确认！"
            + "适用场景：用户明确要求支付某订单。"
            + "流程：首次调用→创建确认项→用户确认→confirmAction→再次调用payOrder执行。"
            + "不适用场景：退款操作（请用 applyRefund）；查询订单（请用 queryOrder）。")
    public String payOrder(
            @ToolParam(description = "订单号", required = true) String orderId,
            @ToolParam(description = "支付方式，如 微信支付/支付宝/银行卡", required = true) String paymentMethod) {
        log.info("[OrderTool] 支付订单: {}, paymentMethod={}", orderId, paymentMethod);

        // ★ Read-before-Edit：必须事先查询过该订单
        String guard = readGuard.requireRead(orderId, "order", "queryOrder");
        if (guard != null) return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, guard);

        OrderEntity order = orderMapper.findByOrderId(orderId);
        if (order == null) {
            return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, "未找到订单 " + orderId);
        }

        // 校验状态
        if (!S_PENDING_PAY.equals(order.getStatus())) {
            return ToolResult.error(AgentErrorCode.INVALID_STATUS,
                    "订单 " + orderId + " 当前状态为「" + order.getStatus() + "」，仅「待付款」订单可以支付");
        }

        // ⭐ 安全检查：先查是否已确认支付
        if (approvalService.checkAndConsume(orderId, "payment")) {
            // 已确认，执行支付
            orderMapper.updatePayment(orderId, S_PENDING_SHIP, paymentMethod);

            log.info("[OrderTool] ✅ 支付成功: orderId={}, paymentMethod={}", orderId, paymentMethod);
            return String.format(
                    "✅ 支付成功！\n订单号：%s\n商品：%s\n金额：¥%s\n付款方式：%s\n状态：待发货\n\n"
                    + "下一步：商家会尽快安排发货，发货后可调用 shipOrder 更新物流信息。",
                    orderId, order.getProductName(), order.getAmount().toPlainString(), paymentMethod);
        }

        // ⭐ 未确认：创建待确认项，返回支付详情让用户确认
        String paymentDetail = String.format(
                "商品：%s\n订单金额：¥%s\n付款方式：%s\n\n"
                + "请确认上述信息和金额是否正确？",
                order.getProductName(), order.getAmount().toPlainString(), paymentMethod);

        approvalService.createApproval(orderId, "payment", paymentDetail);
        log.info("[OrderTool] ⚠️ 支付需用户确认: orderId={}", orderId);

        return "ℹ️ 支付确认提醒\n"
                + "即将为订单 " + orderId + " 进行支付。\n"
                + paymentDetail + "\n"
                + "用户确认后，调用 confirmAction(orderId=\"" + orderId + "\", actionType=\"payment\")，"
                + "然后重新调用 payOrder(orderId=\"" + orderId + "\", paymentMethod=\"" + paymentMethod + "\") 执行支付。";
    }

    // ==================== 取消订单 ====================

    @Transactional
    @Tool(description = "【取消订单】取消指定订单。仅「待付款」或「待发货」状态的订单可以取消。"
            + "取消后状态变为「已取消」。")
    public String cancelOrder(
            @ToolParam(description = "订单号", required = true) String orderId,
            @ToolParam(description = "取消原因", required = true) String reason) {
        log.info("[OrderTool] 取消订单: orderId={}, reason={}", orderId, reason);

        // ★ Read-before-Edit
        String guard = readGuard.requireRead(orderId, "order", "queryOrder");
        if (guard != null) return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, guard);

        OrderEntity order = orderMapper.findByOrderId(orderId);
        if (order == null) {
            return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, "未找到订单 " + orderId);
        }

        // 校验状态：仅待付款/待发货可取消
        if (!S_PENDING_PAY.equals(order.getStatus()) && !S_PENDING_SHIP.equals(order.getStatus())) {
            return ToolResult.error(AgentErrorCode.INVALID_STATUS,
                    "订单 " + orderId + " 当前状态为「" + order.getStatus() + "」，仅「待付款」或「待发货」订单可以取消。"
                    + "已发货的订单如需退款，请使用 applyRefund 申请退款。");
        }

        order.setStatus(S_CANCELLED);
        orderMapper.updateById(order);

        log.info("[OrderTool] ✅ 订单已取消: orderId={}", orderId);
        return String.format(
                "✅ 订单已取消。\n订单号：%s\n商品：%s\n金额：¥%s\n取消原因：%s\n状态：已取消\n\n"
                + "如为已付款订单，退款将在 3-7 个工作日原路返回。",
                orderId, order.getProductName(), order.getAmount().toPlainString(), reason);
    }

    // ==================== 发货 ====================

    @Transactional
    @Tool(description = "【发货】商家发货。仅「待发货」状态的订单可以发货。"
            + "需要提供物流公司名称和快递单号。发货后状态变为「已发货」。"
            + "发货后用户可通过 trackLogistics 查询物流轨迹。")
    public String shipOrder(
            @ToolParam(description = "订单号", required = true) String orderId,
            @ToolParam(description = "物流公司，如 顺丰速运", required = true) String carrier,
            @ToolParam(description = "快递单号", required = true) String trackingNo) {
        log.info("[OrderTool] 发货: orderId={}, carrier={}, trackingNo={}", orderId, carrier, trackingNo);

        // ★ Read-before-Edit
        String guard = readGuard.requireRead(orderId, "order", "queryOrder");
        if (guard != null) return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, guard);

        OrderEntity order = orderMapper.findByOrderId(orderId);
        if (order == null) {
            return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, "未找到订单 " + orderId);
        }

        // 校验状态
        if (!S_PENDING_SHIP.equals(order.getStatus())) {
            return ToolResult.error(AgentErrorCode.INVALID_STATUS,
                    "订单 " + orderId + " 当前状态为「" + order.getStatus() + "」，仅「待发货」订单可以发货");
        }

        // ⭐ 使用 updateById 替代自定义 @Update SQL（已验证 updateById 可靠）
        order.setCarrier(carrier);
        order.setTrackingNo(trackingNo);
        order.setStatus(S_SHIPPED);
        order.setUpdatedAt(LocalDateTime.now());
        int updateRows = orderMapper.updateById(order);
        log.info("[OrderTool] 订单更新影响行数: {} (orderId={})", updateRows, orderId);

        if (updateRows == 0) {
            log.error("[OrderTool] ❌ 订单更新失败: orderId={}", orderId);
            return ToolResult.error(AgentErrorCode.UPDATE_FAILED, "订单 " + orderId + " 状态更新失败，请重试");
        }

        // 创建物流轨迹记录
        try {
            String defaultTrajectory = String.format(
                    "[{\"time\":\"%s\",\"location\":\"\",\"desc\":\"已揽收，包裹已被%s收取\"}]",
                    LocalDateTime.now().format(DTF), carrier);

            OrderLogisticsEntity logistics = OrderLogisticsEntity.builder()
                    .trackingNo(trackingNo)
                    .orderId(orderId)
                    .carrier(carrier)
                    .status("in_transit")
                    .trajectory(defaultTrajectory)
                    .createdAt(LocalDateTime.now())
                    .build();
            int logisticsRows = orderLogisticsMapper.insert(logistics);
            log.info("[OrderTool] 物流记录插入结果: {}", logisticsRows > 0 ? "成功" : "失败");
        } catch (Exception e) {
            log.warn("[OrderTool] 创建物流记录失败（不影响发货）: {}", e.getMessage());
        }

        log.info("[OrderTool] ✅ 发货成功: orderId={}, trackingNo={}", orderId, trackingNo);
        return String.format(
                "✅ 发货成功！\n订单号：%s\n商品：%s\n物流公司：%s\n快递单号：%s\n状态：已发货\n\n"
                + "下一步：用户可通过 trackLogistics(trackingNumber=\"%s\") 查询物流轨迹，"
                + "收到货后可调用 confirmDelivery(orderId=\"%s\") 确认收货。",
                orderId, order.getProductName(), carrier, trackingNo, trackingNo, orderId);
    }

    // ==================== 确认收货 ====================

    @Transactional
    @Tool(description = "【确认收货】买家确认收到商品。仅「已发货」状态的订单可以确认收货。"
            + "确认后状态变为「已签收」。")
    public String confirmDelivery(
            @ToolParam(description = "订单号", required = true) String orderId) {
        log.info("[OrderTool] 确认收货: {}", orderId);

        // ★ Read-before-Edit
        String guard = readGuard.requireRead(orderId, "order", "queryOrder");
        if (guard != null) return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, guard);

        OrderEntity order = orderMapper.findByOrderId(orderId);
        if (order == null) {
            return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, "未找到订单 " + orderId);
        }

        // 校验状态
        if (!S_SHIPPED.equals(order.getStatus())) {
            return ToolResult.error(AgentErrorCode.INVALID_STATUS,
                    "订单 " + orderId + " 当前状态为「" + order.getStatus() + "」，仅「已发货」订单可以确认收货");
        }

        // ⭐ 使用 updateById 替代自定义 @Update SQL
        order.setStatus(S_DELIVERED);
        order.setDeliveredDate(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        int updateRows = orderMapper.updateById(order);
        log.info("[OrderTool] 订单签收更新影响行数: {} (orderId={})", updateRows, orderId);

        if (updateRows == 0) {
            log.error("[OrderTool] ❌ 订单签收更新失败: orderId={}", orderId);
            return ToolResult.error(AgentErrorCode.UPDATE_FAILED, "订单 " + orderId + " 签收状态更新失败，请重试");
        }

        // 更新物流轨迹状态
        try {
            OrderLogisticsEntity logistics = orderLogisticsMapper.findByOrderId(orderId);
            if (logistics != null) {
                // 在轨迹末尾追加签收记录
                String newTrajectory = updateTrajectoryWithDelivery(logistics.getTrajectory());
                logistics.setStatus("delivered");
                logistics.setTrajectory(newTrajectory);
                orderLogisticsMapper.updateById(logistics);
            }
        } catch (Exception e) {
            log.warn("[OrderTool] 更新物流签收状态失败: {}", e.getMessage());
        }

        log.info("[OrderTool] ✅ 确认收货成功: orderId={}", orderId);
        return String.format(
                "✅ 确认收货成功！\n订单号：%s\n商品：%s\n金额：¥%s\n状态：已签收\n\n"
                + "感谢您的购买！如有售后问题可随时联系客服。",
                orderId, order.getProductName(), order.getAmount().toPlainString());
    }

    // ==================== 查询订单 ====================

    @Tool(description = "【查询订单】根据订单号查询订单的详细信息，包括状态、商品、金额、物流信息等。"
            + "适用场景：用户提供订单号要查详情。"
            + "不适用场景：查物流轨迹（请用 trackLogistics）；统计数据（请用 queryOrdersByStatus 等分析工具）。")
    public String queryOrder(
            @ToolParam(description = "订单号，如 ORD-2024001", required = true) String orderId) {
        log.info("[OrderTool] 查询订单: {}", orderId);

        OrderEntity order = orderMapper.findByOrderId(orderId);
        if (order == null) {
            return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, "未找到订单 " + orderId,
                    "确认订单号格式是否正确，当前支持的订单号格式如 ORD-2024001");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📋 订单 %s\n", orderId));
        sb.append(String.format("商品：%s\n", order.getProductName()));
        sb.append(String.format("金额：¥%s\n", order.getAmount().toPlainString()));
        sb.append(String.format("状态：%s\n", getStatusWithIcon(order.getStatus())));
        sb.append(String.format("商品类型：%s\n",
                order.getProductType() != null && !order.getProductType().isEmpty()
                        ? order.getProductType() : "未分类"));

        // 收货信息（下单时有）
        if (order.getContactName() != null && !order.getContactName().isEmpty()) {
            sb.append(String.format("收货人：%s\n", order.getContactName()));
        }
        if (order.getContactPhone() != null && !order.getContactPhone().isEmpty()) {
            sb.append(String.format("联系电话：%s\n", order.getContactPhone()));
        }
        if (order.getShippingAddress() != null && !order.getShippingAddress().isEmpty()) {
            sb.append(String.format("收货地址：%s\n", order.getShippingAddress()));
        }

        // 支付信息
        if (order.getPaymentMethod() != null && !order.getPaymentMethod().isEmpty()) {
            sb.append(String.format("支付方式：%s\n", order.getPaymentMethod()));
        }

        // 物流信息
        if (order.getCarrier() != null && !order.getCarrier().isEmpty()) {
            sb.append(String.format("物流公司：%s\n", order.getCarrier()));
            sb.append(String.format("运单号：%s\n", order.getTrackingNo()));
        }

        if (order.getDeliveredDate() != null) {
            sb.append(String.format("签收日期：%s\n", order.getDeliveredDate().format(DTF)));
        }

        if (order.getCreatedAt() != null) {
            sb.append(String.format("下单时间：%s\n", order.getCreatedAt().format(DTF)));
        }
        if (order.getUpdatedAt() != null) {
            sb.append(String.format("最后更新：%s\n", order.getUpdatedAt().format(DTF)));
        }

        // 根据当前状态提示下一步操作
        String nextStep = getNextStepHint(order.getStatus(), orderId);
        if (nextStep != null) {
            sb.append("\n").append(nextStep);
        }

        // ★ Read-before-Edit：标记已读，供后续修改操作校验
        readGuard.markRead(orderId, "order");

        return sb.toString();
    }

    // ==================== 退款申请 ====================

    @Transactional
    @Tool(description = "【退款申请】提交退款申请。⚠️ 敏感操作，需二次确认。"
            + "适用场景：用户明确要求退款，仅已发货或已签收订单可用。"
            + "流程：首次调用→创建确认项→用户确认→confirmAction→再次调用applyRefund执行。"
            + "不适用场景：待付款/待发货订单取消（请用 cancelOrder）")
    public String applyRefund(
            @ToolParam(description = "订单号", required = true) String orderId,
            @ToolParam(description = "退款原因", required = true) String reason) {
        log.info("[OrderTool] 退款请求: orderId={}, reason={}", orderId, reason);

        // ★ Read-before-Edit：必须事先查询过该订单
        String guard = readGuard.requireRead(orderId, "order", "queryOrder");
        if (guard != null) return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, guard);

        // 先查订单是否存在
        OrderEntity order = orderMapper.findByOrderId(orderId);
        if (order == null) {
            return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, "未找到订单 " + orderId);
        }

        // 校验状态：仅已发货/已签收可申请退款
        if (!S_SHIPPED.equals(order.getStatus()) && !S_DELIVERED.equals(order.getStatus())) {
            return ToolResult.error(AgentErrorCode.INVALID_STATUS,
                    "订单 " + orderId + " 当前状态为「" + order.getStatus() + "」，仅「已发货」或「已签收」订单可以申请退款。"
                    + "「待付款」订单请使用 cancelOrder 取消，"
                    + "「待发货」订单请先联系商家或使用 cancelOrder 取消。");
        }

        // 检查是否已存在退款中记录
        if (S_REFUNDING.equals(order.getStatus())) {
            return ToolResult.error(AgentErrorCode.ALREADY_REFUNDING, "订单 " + orderId + " 已在退款处理中，请耐心等待");
        }

        // ⭐ 安全检查：先查是否已确认
        if (approvalService.checkAndConsume(orderId, "refund")) {
            // 已确认，执行退款
            OrderRefundEntity refund = OrderRefundEntity.builder()
                    .orderId(orderId)
                    .reason(reason)
                    .amount(order.getAmount())
                    .status("completed")
                    .createdBy("system")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            orderRefundMapper.insert(refund);

            // 更新订单状态为退款中
            orderMapper.updateStatusByOrderId(orderId, S_REFUNDING);

            String deliveredDateStr = order.getDeliveredDate() != null
                    ? order.getDeliveredDate().format(DTF) : "未签收";

            log.info("[OrderTool] ✅ 退款已确认并执行: orderId={}, refundId={}", orderId, refund.getId());
            return String.format(
                    "✅ 退款申请已确认并提交。\n订单：%s\n商品：%s\n金额：¥%s\n状态：退款中\n签收日期：%s\n退款原因：%s\n\n退款申请已受理，预计 3-7 个工作日到账。退款编号：%d",
                    orderId, order.getProductName(), order.getAmount().toPlainString(),
                    deliveredDateStr, reason, refund.getId());
        }

        // ⭐ 未确认：创建待确认项，返回提示
        approvalService.createApproval(orderId, "refund", reason);

        String orderInfo = String.format("商品：%s，金额：¥%s，状态：%s",
                order.getProductName(), order.getAmount().toPlainString(), order.getStatus());

        log.info("[OrderTool] ⚠️ 退款需用户确认: orderId={}", orderId);
        return "ℹ️ 退款确认提醒\n"
                + "即将为订单 " + orderId + " 申请退款。\n"
                + orderInfo + "\n"
                + "退款原因：" + reason + "\n"
                + "退款去向：将原路返还至付款账户（尾号 ****）\n\n"
                + "请先询问用户是否确认退款。用户确认后，调用 confirmAction(orderId=\"" + orderId + "\", actionType=\"refund\")，"
                + "然后重新调用 applyRefund(orderId=\"" + orderId + "\", reason=\"" + reason + "\")。";
    }

    // ==================== 确认操作 ====================

    @Tool(description = "【确认操作】确认待处理的支付或退款。"
            + "适用场景：payOrder 或 applyRefund 首次调用后返回确认提示时。"
            + "不适用场景：未调用 payOrder/applyRefund 之前直接调用。")
    public String confirmAction(
            @ToolParam(description = "订单号", required = true) String orderId,
            @ToolParam(description = "操作类型：'payment' 支付确认 / 'refund' 退款确认", required = true) String actionType) {
        log.info("[OrderTool] 确认操作: orderId={}, actionType={}", orderId, actionType);
        boolean success = approvalService.confirmAction(orderId, actionType);

        String nextCall = switch (actionType) {
            case "payment" -> "payOrder(orderId=\"" + orderId + "\", paymentMethod=\"微信支付/支付宝/...\")";
            case "refund" -> "applyRefund(orderId=\"" + orderId + "\", reason=\"...\")";
            default -> null;
        };

        if (success) {
            String msg = "✅ 确认成功。现在可以调用 " + nextCall + " 执行操作。";
            log.info("[OrderTool] ✅ 操作已确认: orderId={}, actionType={}", orderId, actionType);
            return msg;
        }
        return "❌ 确认失败：未找到待确认的操作，请先调用 payOrder 或 applyRefund 创建待确认项。";
    }

    // ==================== 物流查询 ====================

    @Tool(description = "【物流查询】按快递单号查询物流轨迹。"
            + "适用场景：用户提供快递单号要查包裹到哪了。"
            + "不适用场景：查订单基本信息（请用 queryOrder）；没有快递单号时（请用 queryOrder 看物流字段）。")
    public String trackLogistics(
            @ToolParam(description = "快递单号", required = true) String trackingNumber) {
        log.info("[OrderTool] 查物流: {}", trackingNumber);
        if (trackingNumber == null || trackingNumber.isBlank()) {
            return ToolResult.error(AgentErrorCode.TRACKING_REQUIRED, "请提供快递单号", "请提供快递单号");
        }

        OrderLogisticsEntity logistics = orderLogisticsMapper.findByTrackingNo(trackingNumber);
        if (logistics == null) {
            return ToolResult.error(AgentErrorCode.LOGISTICS_NOT_FOUND, "未找到快递单号 " + trackingNumber + " 的物流信息",
                    "确认快递单号是否正确");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📦 快递单号 %s 物流信息\n", trackingNumber));
        sb.append(String.format("所属订单：%s\n", logistics.getOrderId() != null ? logistics.getOrderId() : "未关联"));
        sb.append(String.format("物流公司：%s\n", logistics.getCarrier()));
        sb.append(String.format("状态：%s\n", getLogisticsStatusText(logistics.getStatus())));

        String trajectory = logistics.getTrajectory();
        if (trajectory != null && !trajectory.isEmpty() && !"[]".equals(trajectory)) {
            sb.append("\n最新轨迹：\n");
            try {
                JsonNode array = OBJECT_MAPPER.readTree(trajectory);
                if (array.isArray()) {
                    for (int i = array.size() - 1; i >= 0; i--) {
                        JsonNode node = array.get(i);
                        String time = node.has("time") ? node.get("time").asText() : "";
                        String location = node.has("location") ? node.get("location").asText() : "";
                        String desc = node.has("desc") ? node.get("desc").asText() : "";
                        sb.append(String.format("  %s  %s  %s\n", time, location, desc));
                    }
                }
            } catch (JsonProcessingException e) {
                log.warn("[OrderTool] 轨迹JSON解析失败: {}", e.getMessage());
                sb.append("  ").append(trajectory).append("\n");
            }
        } else {
            sb.append("\n暂无物流轨迹信息。\n");
        }

        return sb.toString();
    }

    // ==================== 私有方法 ====================

    /** 获取带图标的状态显示 */
    private String getStatusWithIcon(String status) {
        if (status == null) return "未知";
        return switch (status) {
            case "待付款" -> "⏳ 待付款";
            case "待发货" -> "📦 待发货";
            case "已发货" -> "🚚 已发货";
            case "已签收" -> "✅ 已签收";
            case "已取消" -> "❌ 已取消";
            case "退款中" -> "🔄 退款中";
            default -> status;
        };
    }

    /** 根据当前状态提示下一步操作 */
    private String getNextStepHint(String status, String orderId) {
        if (status == null) return null;
        return switch (status) {
            case "待付款" ->
                    "💡 提示：订单尚未支付。支付需二次确认，请先询问用户是否确认，"
                    + "确认后调用 payOrder(orderId=\"" + orderId + "\") 执行支付。";
            case "待发货" ->
                    "💡 提示：订单已支付等待商家发货。如需取消，可调用 cancelOrder 取消订单。";
            case "已发货" ->
                    "💡 提示：商品已发出，可调用 trackLogistics 查询物流轨迹。"
                    + "收到货后，可调用 confirmDelivery(orderId=\"" + orderId + "\") 确认收货。";
            case "已签收" ->
                    "💡 提示：商品已签收。如有售后问题，可申请退款。";
            case "已取消" -> null;
            case "退款中" ->
                    "💡 提示：退款处理中，预计 3-7 个工作日到账。";
            default -> null;
        };
    }

    /** 在物流轨迹末尾追加签收记录 */
    private String updateTrajectoryWithDelivery(String trajectory) {
        try {
            if (trajectory == null || trajectory.isEmpty() || "[]".equals(trajectory)) {
                return String.format("[{\"time\":\"%s\",\"location\":\"\",\"desc\":\"已签收\"}]",
                        LocalDateTime.now().format(DTF));
            }
            JsonNode array = OBJECT_MAPPER.readTree(trajectory);
            if (!array.isArray()) return trajectory;

            // 从数组中移除已经存在的签收记录，追加新记录
            String newEntry = String.format("{\"time\":\"%s\",\"location\":\"\",\"desc\":\"已签收，感谢您的购买！\"}",
                    LocalDateTime.now().format(DTF));
            return "[" + newEntry + "," + trajectory.substring(1);
        } catch (JsonProcessingException e) {
            return trajectory;
        }
    }

    /** 根据商品名称推断商品类型 */
    private String inferProductType(String productName) {
        if (productName == null) return "其他";
        String name = productName.toLowerCase();
        if (name.contains("iphone") || name.contains("airpods") || name.contains("macbook")
                || name.contains("apple") || name.contains("ipad") || name.contains("watch")
                || name.contains("手机") || name.contains("电脑") || name.contains("耳机")
                || name.contains("平板")) {
            return "电子产品";
        }
        if (name.contains("生鲜") || name.contains("食品") || name.contains("水果")
                || name.contains("零食") || name.contains("饮料")) {
            return "生鲜食品";
        }
        if (name.contains("定制") || name.contains("刻字") || name.contains("礼品")
                || name.contains("礼物")) {
            return "定制商品";
        }
        return "其他";
    }

    /** 获取物流状态的中文描述 */
    private String getLogisticsStatusText(String status) {
        if (status == null) return "未知";
        return switch (status) {
            case "pending" -> "待发货";
            case "shipped" -> "已发货";
            case "in_transit" -> "运输中";
            case "delivered" -> "已签收";
            case "returned" -> "已退回";
            default -> status;
        };
    }
}
