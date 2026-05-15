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
 * 退款逻辑由 LLM 根据知识库中的 SOP 文档自行判断，工具只负责数据查询和记录。
 */
@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

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
            @ToolParam(description = "订单号，如 ORD-2024001") String orderId) {
        log.info("[OrderTool] 查询订单: {}", orderId);
        Map<String, String> order = ORDERS.get(orderId);
        if (order == null) {
            return "未找到订单 " + orderId + "，请确认订单号是否正确。";
        }
        return String.format(
            "订单 %s\n商品：%s\n金额：¥%s\n状态：%s\n商品类型：%s\n物流公司：%s\n运单号：%s\n预计送达：%s",
            orderId, order.get("product"), order.get("price"),
            order.get("status"), order.get("type"),
            order.get("carrier"), order.get("tracking"), order.get("estimate"));
    }

    @Tool(description = "提交退款申请。返回订单信息和退款原因。退款类型和依据请参考知识库中的 SOP 文档")
    public String applyRefund(
            @ToolParam(description = "订单号") String orderId,
            @ToolParam(description = "退款原因") String reason) {
        log.info("[OrderTool] 退款: orderId={}, reason={}", orderId, reason);
        Map<String, String> order = ORDERS.get(orderId);
        if (order == null) {
            return "未找到订单 " + orderId + "。";
        }
        return String.format(
            "订单：%s\n商品：%s\n金额：¥%s\n状态：%s\n商品类型：%s\n签收日期：%s\n退款原因：%s",
            orderId, order.get("product"), order.get("price"),
            order.get("status"), order.get("type"),
            order.get("deliveredDate") != null && !order.get("deliveredDate").isEmpty()
                    ? order.get("deliveredDate") : "未签收", reason);
    }

    @Tool(description = "查询物流轨迹，需要快递单号")
    public String trackLogistics(
            @ToolParam(description = "快递单号") String trackingNumber) {
        log.info("[OrderTool] 查物流: {}", trackingNumber);
        if (trackingNumber == null || trackingNumber.isBlank()) {
            return "请提供快递单号。";
        }
        return String.format(
            "快递单号 %s 最新轨迹：\n  2026-05-15 08:00  到达 北京分拨中心\n  2026-05-14 22:00  离开 杭州分拨中心\n  2026-05-14 18:00  已揽收",
            trackingNumber);
    }
}
