package com.example.smartassistant.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单客服工具集。
 */
@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    // 模拟订单数据
    private static final Map<String, Map<String, String>> ORDERS = new ConcurrentHashMap<>();
    static {
        ORDERS.put("ORD-12345", Map.of(
            "status", "已发货", "carrier", "顺丰速运", "tracking", "SF1234567890",
            "estimate", "明天 18:00 前", "product", "iPhone 15 Pro", "price", "8999"
        ));
        ORDERS.put("ORD-12346", Map.of(
            "status", "待发货", "carrier", "", "tracking", "",
            "estimate", "2-3 个工作日", "product", "AirPods Pro", "price", "1999"
        ));
        ORDERS.put("ORD-12347", Map.of(
            "status", "已签收", "carrier", "圆通速递", "tracking", "YT987654321",
            "estimate", "已签收", "product", "MacBook Air", "price", "7999"
        ));
    }

    @Tool(description = "根据订单号查询订单状态，返回订单当前进度、物流单号和预计送达时间")
    public String queryOrder(
            @ToolParam(description = "订单号，如 ORD-12345") String orderId) {
        log.info("[OrderTool] 查询订单: {}", orderId);
        Map<String, String> order = ORDERS.get(orderId);
        if (order == null) {
            return "未找到订单 " + orderId + "，请确认订单号是否正确。";
        }
        return String.format(
            "订单 %s 当前状态：%s\n商品：%s\n金额：%s 元\n物流公司：%s\n运单号：%s\n预计送达：%s",
            orderId, order.get("status"), order.get("product"), order.get("price"),
            order.get("carrier"), order.get("tracking"), order.get("estimate"));
    }

    @Tool(description = "发起退款申请，需要订单号和退款原因。退款将在 1-3 个工作日内原路返回")
    public String applyRefund(
            @ToolParam(description = "订单号") String orderId,
            @ToolParam(description = "退款原因") String reason) {
        log.info("[OrderTool] 退款: orderId={}, reason={}", orderId, reason);
        return String.format(
            "已为您提交订单 %s 的退款申请。\n退款原因：%s\n退款金额：将按原支付方式原路返回\n预计到账：1-3 个工作日\n\n如需取消退款，请尽快联系客服。",
            orderId, reason);
    }

    @Tool(description = "查询物流轨迹，需要快递单号。返回最新的物流流转信息")
    public String trackLogistics(
            @ToolParam(description = "快递单号") String trackingNumber) {
        log.info("[OrderTool] 查物流: {}", trackingNumber);
        if (trackingNumber == null || trackingNumber.isBlank()) {
            return "请提供快递单号。";
        }
        return String.format(
            "快递单号 %s 最新轨迹：\n  2026-05-15 08:00  到达 北京分拨中心\n  2026-05-14 22:00  离开 杭州分拨中心\n  2026-05-14 18:00  已揽收\n\n预计明天送达，请保持电话畅通。",
            trackingNumber);
    }
}
