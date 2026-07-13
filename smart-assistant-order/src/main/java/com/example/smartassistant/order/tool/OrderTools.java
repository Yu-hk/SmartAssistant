/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.order.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.tool.client.ToolRegistryClient;
import com.example.smartassistant.common.idempotent.TaskLogService;
import com.example.smartassistant.common.tool.ReadBeforeEditGuard;
import com.example.smartassistant.common.tool.ToolResult;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.LogisticsDTO;
import com.example.smartassistant.common.tool.spi.dto.OrderDTO;
import com.example.smartassistant.common.tool.spi.dto.RefundDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
 * Order customer service toolset (database persistence version via SPI).
 * <p>
 * Covers the full order lifecycle:
 * <pre>
 *   Place → Pay → Ship → Confirm Delivery
 *    │                │
 *    └──→ Cancel ←────┘
 *                         │
 *   Shipped/Delivered → Refund → Refunding
 * </pre>
 * </p>
 */
@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final AtomicLong ORDER_ID_COUNTER = new AtomicLong(System.currentTimeMillis() % 100000);

    private static final String S_PENDING_PAY = "待付款";
    private static final String S_PENDING_SHIP = "待发货";
    private static final String S_SHIPPED = "已发货";
    private static final String S_DELIVERED = "已签收";
    private static final String S_CANCELLED = "已取消";
    private static final String S_REFUNDING = "退款中";

    private final OrderDataProvider orderData;
    private final ReadBeforeEditGuard readGuard;
    private final ToolRegistry toolRegistry;
    private final ToolRegistryClient registryClient;
    private final TaskLogService taskLogService;

    public OrderTools(OrderDataProvider orderData,
                      ReadBeforeEditGuard readGuard,
                      ToolRegistry toolRegistry,
                      ToolRegistryClient registryClient,
                      TaskLogService taskLogService) {
        this.orderData = orderData;
        this.readGuard = readGuard;
        this.toolRegistry = toolRegistry;
        this.registryClient = registryClient;
        this.taskLogService = taskLogService;
    }

    @PostConstruct
    public void initTools() {
        java.util.List.of(
                ToolDefinition.read("queryOrder", "查询订单详情"),
                ToolDefinition.read("trackLogistics", "查询物流轨迹"),
                ToolDefinition.highRisk("createOrder", "创建新订单", true),
                ToolDefinition.highRisk("payOrder", "完成订单支付", true),
                ToolDefinition.highRisk("cancelOrder", "取消订单", true),
                ToolDefinition.highRisk("applyRefund", "提交退款申请", true),
                ToolDefinition.write("shipOrder", "商家发货", com.example.smartassistant.common.gateway.tool.ToolRiskLevel.MEDIUM),
                ToolDefinition.write("confirmDelivery", "确认收货", com.example.smartassistant.common.gateway.tool.ToolRiskLevel.MEDIUM),
                ToolDefinition.highRisk("confirmAction", "确认支付/退款操作", true)
        ).forEach(def -> {
            toolRegistry.register(def);
            registryClient.registerWithFallback(def, toolRegistry);
        });
    }

    private String idempotentKey(String action, String orderId) {
        return "order:" + action + ":" + orderId;
    }

    private String idempotentRequestId(String action, Object... params) {
        StringBuilder sb = new StringBuilder(action);
        for (Object p : params) {
            if (p != null) sb.append("|").append(p);
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return "idem-" + action + "-" + hex.substring(0, 16);
        } catch (Exception e) {
            return "idem-" + action + "-" + sb.length();
        }
    }

    // ==================== Create Order ====================

    @Transactional
    @Tool(description = "【下单】创建新的订单。用户提供商品名称、金额和收货信息，系统自动生成订单号。"
            + "下单成功后状态为「待付款」，后续可调用 payOrder 完成支付。")
    public String createOrder(
            @ToolParam(description = "用户ID，如 12345") Long userId,
            @ToolParam(description = "商品名称，如 iPhone 15 Pro 256GB") String productName,
            @ToolParam(description = "商品金额，如 8999.00") BigDecimal amount,
            @ToolParam(description = "收货人姓名") String contactName,
            @ToolParam(description = "收货人电话") String contactPhone,
            @ToolParam(description = "收货地址") String shippingAddress,
            @ToolParam(description = "商品类型，如 电子产品/定制商品/生鲜食品，留空则自动识别", required = false) String productType) {
        log.info("[OrderTool] 创建订单: userId={}, productName={}, amount={}", userId, productName, amount);

        String requestId = idempotentRequestId("createOrder", userId, productName, amount, shippingAddress);
        return taskLogService.executeIfNotDone(requestId, "order:create",
                idempotentKey("create", productName + "|" + userId), () -> {
                    String orderId = String.format("ORD-%d%04d",
                            System.currentTimeMillis() % 1000000,
                            ORDER_ID_COUNTER.incrementAndGet() % 10000);

                    String type = (productType != null && !productType.isBlank()) ? productType : inferProductType(productName);

                    OrderDTO order = OrderDTO.builder()
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
                            .requestId(requestId)
                            .build();

                    try {
                        orderData.insertOrder(order);
                    } catch (org.springframework.dao.DuplicateKeyException e) {
                        log.warn("[OrderTool] ⚠️ 重复订单请求被拦截: requestId={}", requestId);
                        return "⚠️ 检测到重复的订单创建请求。订单已存在，请勿重复提交。如需查询请使用 queryOrder。";
                    }
                    log.info("[OrderTool] ✅ 订单创建成功: orderId={}", orderId);

                    return String.format(
                            """
                                    📦 订单创建成功！
                                    订单号：%s
                                    商品：%s
                                    金额：¥%.2f
                                    商品类型：%s
                                    收货人：%s
                                    联系电话：%s
                                    收货地址：%s
                                    状态：待付款
                                    
                                    下一步：请提醒用户核对收货信息并完成付款，可调用 payOrder(orderId="%s") 进行支付。""",
                            orderId, productName, amount, type,
                            contactName, contactPhone, shippingAddress,
                            orderId);
                });
    }

    // ==================== Payment ====================

    @Transactional
    @Tool(description = "【支付】完成订单支付。⚠️ 资金敏感操作，需二次确认！"
            + "适用场景：用户明确要求支付某订单。"
            + "流程：首次调用→创建确认项→用户确认→confirmAction→再次调用payOrder执行。"
            + "不适用场景：退款操作（请用 applyRefund）；查询订单（请用 queryOrder）。")
    public String payOrder(
            @ToolParam(description = "订单号，如 ORD-2024001") String orderId,
            @ToolParam(description = "支付方式，如 微信支付/支付宝/银行卡") String paymentMethod) {
        log.info("[OrderTool] 支付订单: {}, paymentMethod={}", orderId, paymentMethod);

        String guard = readGuard.requireRead(orderId, "order", "queryOrder");
        if (guard != null) return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, guard);

        OrderDTO order = orderData.findOrderByOrderId(orderId);
        if (order == null) {
            return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, "未找到订单 " + orderId);
        }

        if (!S_PENDING_PAY.equals(order.getStatus())) {
            return ToolResult.error(AgentErrorCode.INVALID_STATUS,
                    "订单 " + orderId + " 当前状态为「" + order.getStatus() + "」，仅「待付款」订单可以支付");
        }

        if (orderData.checkAndConsume(orderId, "payment")) {
            orderData.updatePayment(orderId, S_PENDING_SHIP, paymentMethod);

            log.info("[OrderTool] ✅ 支付成功: orderId={}, paymentMethod={}", orderId, paymentMethod);
            return String.format(
                    """
                            ✅ 支付成功！
                            订单号：%s
                            商品：%s
                            金额：¥%s
                            付款方式：%s
                            状态：待发货
                            
                            下一步：商家会尽快安排发货，发货后可调用 shipOrder 更新物流信息。""",
                    orderId, order.getProductName(), order.getAmount().toPlainString(), paymentMethod);
        }

        String paymentDetail = String.format(
                """
                        商品：%s
                        订单金额：¥%s
                        付款方式：%s
                        
                        请确认上述信息和金额是否正确？""",
                order.getProductName(), order.getAmount().toPlainString(), paymentMethod);

        orderData.createApproval(orderId, "payment", paymentDetail);
        log.info("[OrderTool] ⚠️ 支付需用户确认: orderId={}", orderId);

        return "ℹ️ 支付确认提醒\n"
                + "即将为订单 " + orderId + " 进行支付。\n"
                + paymentDetail + "\n"
                + "用户确认后，调用 confirmAction(orderId=\"" + orderId + "\", actionType=\"payment\")，"
                + "然后重新调用 payOrder(orderId=\"" + orderId + "\", paymentMethod=\"" + paymentMethod + "\") 执行支付。";
    }

    // ==================== Cancel Order ====================

    @Transactional
    @Tool(description = "【取消订单】取消指定订单。仅「待付款」或「待发货」状态的订单可以取消。"
            + "取消后状态变为「已取消」。")
    public String cancelOrder(
            @ToolParam(description = "订单号，如 ORD-2024001", required = true) String orderId,
            @ToolParam(description = "取消原因，如 '不想要了'/'商品与描述不符'", required = true) String reason) {
        log.info("[OrderTool] 取消订单: orderId={}, reason={}", orderId, reason);

        String requestId = idempotentRequestId("cancelOrder", orderId);
        return taskLogService.executeIfNotDone(requestId, "order:cancel",
                idempotentKey("cancel", orderId), () -> doCancel(orderId, reason));
    }

    private String doCancel(String orderId, String reason) {
        String guard = readGuard.requireRead(orderId, "order", "queryOrder");
        if (guard != null) return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, guard);

        OrderDTO order = orderData.findOrderByOrderId(orderId);
        if (order == null) {
            return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, "未找到订单 " + orderId);
        }

        if (!S_PENDING_PAY.equals(order.getStatus()) && !S_PENDING_SHIP.equals(order.getStatus())) {
            return ToolResult.error(AgentErrorCode.INVALID_STATUS,
                    "订单 " + orderId + " 当前状态为「" + order.getStatus() + "」，仅「待付款」或「待发货」订单可以取消。"
                    + "已发货的订单如需退款，请使用 applyRefund 申请退款。");
        }

        order.setStatus(S_CANCELLED);
        orderData.updateOrderById(order);

        log.info("[OrderTool] ✅ 订单已取消: orderId={}", orderId);
        return String.format(
                """
                        ✅ 订单已取消。
                        订单号：%s
                        商品：%s
                        金额：¥%s
                        取消原因：%s
                        状态：已取消
                        
                        如为已付款订单，退款将在 3-7 个工作日原路返回。""",
                orderId, order.getProductName(), order.getAmount().toPlainString(), reason);
    }

    // ==================== Ship ====================

    @Transactional
    @Tool(description = "【发货】商家发货。仅「待发货」状态的订单可以发货。"
            + "需要提供物流公司名称和快递单号。发货后状态变为「已发货」。"
            + "发货后用户可通过 trackLogistics 查询物流轨迹。")
    public String shipOrder(
            @ToolParam(description = "订单号，如 ORD-2024001") String orderId,
            @ToolParam(description = "物流公司，如 顺丰速运") String carrier,
            @ToolParam(description = "快递单号，如 SF1234567890") String trackingNo) {
        log.info("[OrderTool] 发货: orderId={}, carrier={}, trackingNo={}", orderId, carrier, trackingNo);

        String requestId = idempotentRequestId("shipOrder", orderId, trackingNo);
        return taskLogService.executeIfNotDone(requestId, "order:ship",
                idempotentKey("ship", orderId), () -> doShip(orderId, carrier, trackingNo));
    }

    private String doShip(String orderId, String carrier, String trackingNo) {
        String guard = readGuard.requireRead(orderId, "order", "queryOrder");
        if (guard != null) return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, guard);

        OrderDTO order = orderData.findOrderByOrderId(orderId);
        if (order == null) {
            return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, "未找到订单 " + orderId);
        }

        if (!S_PENDING_SHIP.equals(order.getStatus())) {
            return ToolResult.error(AgentErrorCode.INVALID_STATUS,
                    "订单 " + orderId + " 当前状态为「" + order.getStatus() + "」，仅「待发货」订单可以发货");
        }

        order.setCarrier(carrier);
        order.setTrackingNo(trackingNo);
        order.setStatus(S_SHIPPED);
        order.setUpdatedAt(LocalDateTime.now());
        int updateRows = orderData.updateOrderById(order);
        log.info("[OrderTool] 订单更新影响行数: {} (orderId={})", updateRows, orderId);

        if (updateRows == 0) {
            log.error("[OrderTool] ❌ 订单更新失败: orderId={}", orderId);
            return ToolResult.error(AgentErrorCode.UPDATE_FAILED, "订单 " + orderId + " 状态更新失败，请重试");
        }

        try {
            String defaultTrajectory = String.format(
                    "[{\"time\":\"%s\",\"location\":\"\",\"desc\":\"已揽收，包裹已被%s收取\"}]",
                    LocalDateTime.now().format(DTF), carrier);

            LogisticsDTO logistics = LogisticsDTO.builder()
                    .trackingNo(trackingNo)
                    .orderId(orderId)
                    .companyName(carrier)
                    .status("in_transit")
                    .logisticsDetail(defaultTrajectory)
                    .logisticsTime(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .build();
            int logisticsRows = orderData.insertLogistics(logistics);
            log.info("[OrderTool] 物流记录插入结果: {}", logisticsRows > 0 ? "成功" : "失败");
        } catch (Exception e) {
            log.warn("[OrderTool] 创建物流记录失败（不影响发货）: {}", e.getMessage());
        }

        log.info("[OrderTool] ✅ 发货成功: orderId={}, trackingNo={}", orderId, trackingNo);
        return String.format(
                """
                        ✅ 发货成功！
                        订单号：%s
                        商品：%s
                        物流公司：%s
                        快递单号：%s
                        状态：已发货
                        
                        下一步：用户可通过 trackLogistics(trackingNumber="%s") 查询物流轨迹，\
                        收到货后可调用 confirmDelivery(orderId="%s") 确认收货。""",
                orderId, order.getProductName(), carrier, trackingNo, trackingNo, orderId);
    }

    // ==================== Confirm Delivery ====================

    @Transactional
    @Tool(description = "【确认收货】买家确认收到商品。仅「已发货」状态的订单可以确认收货。"
            + "确认后状态变为「已签收」。")
    public String confirmDelivery(
            @ToolParam(description = "订单号，如 ORD-2024001", required = true) String orderId) {
        log.info("[OrderTool] 确认收货: {}", orderId);

        String requestId = idempotentRequestId("confirmDelivery", orderId);
        return taskLogService.executeIfNotDone(requestId, "order:delivery",
                idempotentKey("delivery", orderId), () -> doConfirmDelivery(orderId));
    }

    private String doConfirmDelivery(String orderId) {
        String guard = readGuard.requireRead(orderId, "order", "queryOrder");
        if (guard != null) return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, guard);

        OrderDTO order = orderData.findOrderByOrderId(orderId);
        if (order == null) {
            return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, "未找到订单 " + orderId);
        }

        if (!S_SHIPPED.equals(order.getStatus())) {
            return ToolResult.error(AgentErrorCode.INVALID_STATUS,
                    "订单 " + orderId + " 当前状态为「" + order.getStatus() + "」，仅「已发货」订单可以确认收货");
        }

        order.setStatus(S_DELIVERED);
        order.setDeliveredDate(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        int updateRows = orderData.updateOrderById(order);
        log.info("[OrderTool] 订单签收更新影响行数: {} (orderId={})", updateRows, orderId);

        if (updateRows == 0) {
            log.error("[OrderTool] ❌ 订单签收更新失败: orderId={}", orderId);
            return ToolResult.error(AgentErrorCode.UPDATE_FAILED, "订单 " + orderId + " 签收状态更新失败，请重试");
        }

        try {
            LogisticsDTO logistics = orderData.findLogisticsByOrderId(orderId);
            if (logistics != null) {
                String newDetail = updateTrajectoryWithDelivery(logistics.getLogisticsDetail());
                logistics.setStatus("delivered");
                logistics.setLogisticsDetail(newDetail);
                orderData.updateLogisticsById(logistics);
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

    // ==================== Query Order ====================

    @Tool(description = "【查询订单】根据订单号查询订单的详细信息，包括状态、商品、金额、物流信息等。"
            + "适用场景：用户提供订单号要查详情。"
            + "不适用场景：查物流轨迹（请用 trackLogistics）；统计数据（请用 queryOrdersByStatus 等分析工具）。")
    public String queryOrder(
            @ToolParam(description = "订单号，如 ORD-2024001") String orderId) {
        log.info("[OrderTool] 查询订单: {}", orderId);

        OrderDTO order = orderData.findOrderByOrderId(orderId);
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

        if (order.getContactName() != null && !order.getContactName().isEmpty()) {
            sb.append(String.format("收货人：%s\n", order.getContactName()));
        }
        if (order.getContactPhone() != null && !order.getContactPhone().isEmpty()) {
            sb.append(String.format("联系电话：%s\n", order.getContactPhone()));
        }
        if (order.getShippingAddress() != null && !order.getShippingAddress().isEmpty()) {
            sb.append(String.format("收货地址：%s\n", order.getShippingAddress()));
        }
        if (order.getPaymentMethod() != null && !order.getPaymentMethod().isEmpty()) {
            sb.append(String.format("支付方式：%s\n", order.getPaymentMethod()));
        }
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

        String nextStep = getNextStepHint(order.getStatus(), orderId);
        if (nextStep != null) {
            sb.append("\n").append(nextStep);
        }

        readGuard.markRead(orderId, "order");
        return sb.toString();
    }

    // ==================== Refund ====================

    @Transactional
    @Tool(description = "【退款申请】提交退款申请。⚠️ 敏感操作，需二次确认。"
            + "适用场景：用户明确要求退款，仅已发货或已签收订单可用。"
            + "流程：首次调用→创建确认项→用户确认→confirmAction→再次调用applyRefund执行。"
            + "不适用场景：待付款/待发货订单取消（请用 cancelOrder）")
    public String applyRefund(
            @ToolParam(description = "订单号，如 ORD-2024001") String orderId,
            @ToolParam(description = "退款原因，如 '商品质量问题'/'七天无理由退货'") String reason) {
        log.info("[OrderTool] 退款请求: orderId={}, reason={}", orderId, reason);

        String guard = readGuard.requireRead(orderId, "order", "queryOrder");
        if (guard != null) return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, guard);

        OrderDTO order = orderData.findOrderByOrderId(orderId);
        if (order == null) {
            return ToolResult.error(AgentErrorCode.ORDER_NOT_FOUND, "未找到订单 " + orderId);
        }

        if (S_REFUNDING.equals(order.getStatus())) {
            return ToolResult.error(AgentErrorCode.ALREADY_REFUNDING, "订单 " + orderId + " 已在退款处理中，请耐心等待");
        }

        if (!S_SHIPPED.equals(order.getStatus()) && !S_DELIVERED.equals(order.getStatus())) {
            return ToolResult.error(AgentErrorCode.INVALID_STATUS,
                    "订单 " + orderId + " 当前状态为「" + order.getStatus() + "」，仅「已发货」或「已签收」订单可以申请退款。"
                    + "「待付款」订单请使用 cancelOrder 取消，"
                    + "「待发货」订单请先联系商家或使用 cancelOrder 取消。");
        }

        if (orderData.checkAndConsume(orderId, "refund")) {
            RefundDTO refund = RefundDTO.builder()
                    .orderId(orderId)
                    .reason(reason)
                    .amount(order.getAmount())
                    .status("completed")
                    .createTime(LocalDateTime.now())
                    .build();
            orderData.insertRefund(refund);

            orderData.updateStatusByOrderId(orderId, S_REFUNDING);

            String deliveredDateStr = order.getDeliveredDate() != null
                    ? order.getDeliveredDate().format(DTF) : "未签收";

            log.info("[OrderTool] ✅ 退款已确认并执行: orderId={}", orderId);
            return String.format(
                    "✅ 退款申请已确认并提交。\n订单：%s\n商品：%s\n金额：¥%s\n状态：退款中\n签收日期：%s\n退款原因：%s\n\n退款申请已受理，预计 3-7 个工作日到账。",
                    orderId, order.getProductName(), order.getAmount().toPlainString(),
                    deliveredDateStr, reason);
        }

        orderData.createApproval(orderId, "refund", reason);

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

    // ==================== Confirm Action ====================

    @Tool(description = "【确认操作】确认待处理的支付或退款。"
            + "适用场景：payOrder 或 applyRefund 首次调用后返回确认提示时。"
            + "不适用场景：未调用 payOrder/applyRefund 之前直接调用。")
    public String confirmAction(
            @ToolParam(description = "订单号，如 ORD-2024001", required = true) String orderId,
            @ToolParam(description = "操作类型：'payment' 支付确认 / 'refund' 退款确认", required = true) String actionType) {
        log.info("[OrderTool] 确认操作: orderId={}, actionType={}", orderId, actionType);
        boolean success = orderData.confirmAction(orderId, actionType);

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

    // ==================== Logistics Tracking ====================

    @Tool(description = "【物流查询】按快递单号查询物流轨迹。"
            + "适用场景：用户提供快递单号要查包裹到哪了。"
            + "不适用场景：查订单基本信息（请用 queryOrder）；没有快递单号时（请用 queryOrder 看物流字段）。")
    public String trackLogistics(
            @ToolParam(description = "快递单号，如 SF1234567890", required = true) String trackingNumber) {
        log.info("[OrderTool] 查物流: {}", trackingNumber);
        if (trackingNumber == null || trackingNumber.isBlank()) {
            return ToolResult.error(AgentErrorCode.TRACKING_REQUIRED, "请提供快递单号", "请提供快递单号");
        }

        LogisticsDTO logistics = orderData.findLogisticsByTrackingNo(trackingNumber);
        if (logistics == null) {
            return ToolResult.error(AgentErrorCode.LOGISTICS_NOT_FOUND, "未找到快递单号 " + trackingNumber + " 的物流信息",
                    "确认快递单号是否正确");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📦 快递单号 %s 物流信息\n", trackingNumber));
        sb.append(String.format("所属订单：%s\n", logistics.getOrderId() != null ? logistics.getOrderId() : "未关联"));
        sb.append(String.format("物流公司：%s\n", logistics.getCompanyName()));
        sb.append(String.format("状态：%s\n", getLogisticsStatusText(logistics.getStatus())));

        String detail = logistics.getLogisticsDetail();
        if (detail != null && !detail.isEmpty() && !"[]".equals(detail)) {
            sb.append("\n最新轨迹：\n");
            try {
                JsonNode array = OBJECT_MAPPER.readTree(detail);
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
                sb.append("  ").append(detail).append("\n");
            }
        } else {
            sb.append("\n暂无物流轨迹信息。\n");
        }

        return sb.toString();
    }

    // ==================== Private Methods ====================

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
            case "退款中" ->
                    "💡 提示：退款处理中，预计 3-7 个工作日到账。";
            default -> null;
        };
    }

    private String updateTrajectoryWithDelivery(String trajectory) {
        try {
            if (trajectory == null || trajectory.isEmpty() || "[]".equals(trajectory)) {
                return String.format("[{\"time\":\"%s\",\"location\":\"\",\"desc\":\"已签收\"}]",
                        LocalDateTime.now().format(DTF));
            }
            JsonNode array = OBJECT_MAPPER.readTree(trajectory);
            if (!array.isArray()) return trajectory;

            String newEntry = String.format("{\"time\":\"%s\",\"location\":\"\",\"desc\":\"已签收，感谢您的购买！\"}",
                    LocalDateTime.now().format(DTF));
            return "[" + newEntry + "," + trajectory.substring(1);
        } catch (JsonProcessingException e) {
            return trajectory;
        }
    }

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
