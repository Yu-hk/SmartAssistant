package com.example.smartassistant.tools;

import com.example.smartassistant.common.tool.ToolResult;
import com.example.smartassistant.service.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单客服工具集。
 * 退款逻辑由 LLM 根据知识库中的 SOP 文档自行判断，工具只负责数据查询和记录。
 * <p>
 * ⚠️ 敏感操作（如退款）需要先通过 {@link #confirmAction} 获取用户确认后才能执行。
 * </p>
 */
@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    private final ApprovalService approvalService;

    public OrderTools(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    private static final Map<String, Map<String, String>> ORDERS = new ConcurrentHashMap<>();
    static {
        ORDERS.put("ORD-2024001", Map.of(
            "status", "已发货", "carrier", "顺丰速运", "tracking", "SF1234567890",
            "estimate", "2026-05-18 前", "product", "iPhone 15 Pro 256GB",
            "price", "8999.00", "type", "电子产品", "deliveredDate", ""));
        ORDERS.put("ORD-2024002", Map.of(
            "status", "待发货", "carrier", "", "tracking", "",
            "estimate", "2-3 个工作日", "product", "AirPods Pro 第二代",
            "price", "1999.00", "type", "电子产品", "deliveredDate", ""));
        ORDERS.put("ORD-2024003", Map.of(
            "status", "已签收", "carrier", "圆通速递", "tracking", "YT987654321",
            "estimate", "已签收", "product", "MacBook Air M3",
            "price", "10999.00", "type", "电子产品", "deliveredDate", "2026-05-12"));
        ORDERS.put("ORD-2024004", Map.of(
            "status", "退款中", "carrier", "", "tracking", "",
            "estimate", "", "product", "Apple Watch Series 9",
            "price", "3199.00", "type", "电子产品", "deliveredDate", ""));
        ORDERS.put("ORD-C001", Map.of(
            "status", "已签收", "carrier", "中通快递", "tracking", "ZT123456789",
            "estimate", "已签收", "product", "定制刻字礼物",
            "price", "399.00", "type", "定制商品", "deliveredDate", "2026-05-05"));
        ORDERS.put("ORD-F001", Map.of(
            "status", "已签收", "carrier", "京东物流", "tracking", "JD987654321",
            "estimate", "已签收", "product", "进口生鲜礼盒",
            "price", "599.00", "type", "生鲜食品", "deliveredDate", "2026-05-11"));
    }

    @Tool(description = "根据订单号查询订单状态、商品、金额、物流信息")
    public String queryOrder(
            @ToolParam(description = "订单号，如 ORD-2024001", required = true) String orderId) {
        log.info("[OrderTool] 查询订单: {}", orderId);
        Map<String, String> order = ORDERS.get(orderId);
        if (order == null) {
            return ToolResult.error("ORDER_NOT_FOUND", "未找到订单 " + orderId,
                    false, "确认订单号格式是否正确");
        }
        return String.format(
            "订单 %s\n商品：%s\n金额：¥%s\n状态：%s\n商品类型：%s\n物流公司：%s\n运单号：%s\n预计送达：%s",
            orderId, order.get("product"), order.get("price"),
            order.get("status"), order.get("type"),
            order.get("carrier"), order.get("tracking"), order.get("estimate"));
    }

    @Tool(description = "提交退款申请。⚠️ 此为敏感操作，首次调用时需先获取用户确认。"
            + "如果返回确认提醒，请先询问用户是否确认，用户确认后调用 confirmAction(orderId, \"refund\")，再重新调用 applyRefund。")
    public String applyRefund(
            @ToolParam(description = "订单号", required = true) String orderId,
            @ToolParam(description = "退款原因", required = true) String reason) {
        log.info("[OrderTool] 退款请求: orderId={}, reason={}", orderId, reason);

        // ⭐ 安全检查：先查是否已确认
        if (approvalService.checkAndConsume(orderId, "refund")) {
            // 已确认，执行退款
            Map<String, String> order = ORDERS.get(orderId);
            if (order == null) {
                return ToolResult.error("ORDER_NOT_FOUND", "未找到订单 " + orderId, false);
            }

            log.info("[OrderTool] ✅ 退款已确认并执行: orderId={}", orderId);
            return String.format(
                "✅ 退款申请已确认并提交。\n订单：%s\n商品：%s\n金额：¥%s\n状态：%s\n商品类型：%s\n签收日期：%s\n退款原因：%s\n\n退款申请已受理，预计 3-7 个工作日到账。",
                orderId, order.get("product"), order.get("price"),
                order.get("status"), order.get("type"),
                order.get("deliveredDate") != null && !order.get("deliveredDate").isEmpty()
                        ? order.get("deliveredDate") : "未签收", reason);
        }

        // ⭐ 未确认：创建待确认项，返回提示
        approvalService.createApproval(orderId, "refund", reason);

        Map<String, String> order = ORDERS.get(orderId);
        String orderInfo = (order != null)
                ? String.format("商品：%s，金额：¥%s，状态：%s", order.get("product"), order.get("price"), order.get("status"))
                : "";

        log.info("[OrderTool] ⚠️ 退款需用户确认: orderId={}", orderId);
        return "ℹ️ 确认提醒\n"
                + "即将为订单 " + orderId + " 申请退款。\n"
                + (orderInfo.isEmpty() ? "" : orderInfo + "\n")
                + "原因：" + reason + "\n\n"
                + "请先询问用户是否确认退款。用户确认后，调用 confirmAction(orderId=\"" + orderId + "\", actionType=\"refund\")，"
                + "然后重新调用 applyRefund(orderId=\"" + orderId + "\", reason=\"" + reason + "\")。";
    }

    @Tool(description = "确认一个待处理的操作（如退款）。在执行 applyRefund 等敏感操作前，必须先获取用户的明确确认，然后调用此工具。")
    public String confirmAction(
            @ToolParam(description = "订单号", required = true) String orderId,
            @ToolParam(description = "操作类型，如 'refund'", required = true) String actionType) {
        log.info("[OrderTool] 确认操作: orderId={}, actionType={}", orderId, actionType);
        boolean success = approvalService.confirmAction(orderId, actionType);
        if (success) {
            return "✅ 确认成功。现在可以调用 applyRefund(orderId=\"" + orderId + "\", reason=\"...\") 执行退款操作。";
        }
        return "❌ 确认失败：未找到待确认的操作，请先调用 applyRefund 创建待确认项。";
    }

    @Tool(description = "查询物流轨迹，需要快递单号")
    public String trackLogistics(
            @ToolParam(description = "快递单号", required = true) String trackingNumber) {
        log.info("[OrderTool] 查物流: {}", trackingNumber);
        if (trackingNumber == null || trackingNumber.isBlank()) {
            return ToolResult.error("TRACKING_REQUIRED", "请提供快递单号", false, "请提供快递单号");
        }
        return String.format(
            "快递单号 %s 最新轨迹：\n  2026-05-15 08:00  到达 北京分拨中心\n  2026-05-14 22:00  离开 杭州分拨中心\n  2026-05-14 18:00  已揽收",
            trackingNumber);
    }
}
