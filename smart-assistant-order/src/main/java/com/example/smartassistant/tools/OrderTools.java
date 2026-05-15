package com.example.smartassistant.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单客服工具集。
 * 依据《中华人民共和国消费者权益保护法》及《网络交易监督管理办法》执行退款流程。
 */
@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    private static final Map<String, Map<String, String>> ORDERS = new ConcurrentHashMap<>();
    static {
        ORDERS.put("ORD-2024001", Map.of(
            "status", "已发货", "carrier", "顺丰速运", "tracking", "SF1234567890",
            "estimate", "2026-05-18 前", "product", "iPhone 15 Pro 256GB",
            "price", "8999.00", "type", "电子产品", "orderDate", "2026-05-10",
            "deliveredDate", ""
        ));
        ORDERS.put("ORD-2024002", Map.of(
            "status", "待发货", "carrier", "", "tracking", "",
            "estimate", "2-3 个工作日", "product", "AirPods Pro 第二代",
            "price", "1999.00", "type", "电子产品", "orderDate", "2026-05-12",
            "deliveredDate", ""
        ));
        ORDERS.put("ORD-2024003", Map.of(
            "status", "已签收", "carrier", "圆通速递", "tracking", "YT987654321",
            "estimate", "已签收", "product", "MacBook Air M3",
            "price", "10999.00", "type", "电子产品", "orderDate", "2026-05-08",
            "deliveredDate", "2026-05-12"
        ));
        ORDERS.put("ORD-2024004", Map.of(
            "status", "退款中", "carrier", "", "tracking", "",
            "estimate", "", "product", "Apple Watch Series 9",
            "price", "3199.00", "type", "电子产品", "orderDate", "2026-05-13",
            "deliveredDate", ""
        ));
        ORDERS.put("ORD-2024005", Map.of(
            "status", "待付款", "carrier", "", "tracking", "",
            "estimate", "", "product", "iPad Air M2",
            "price", "4799.00", "type", "电子产品", "orderDate", "2026-05-14",
            "deliveredDate", ""
        ));
        ORDERS.put("ORD-C001", Map.of(
            "status", "已签收", "carrier", "中通快递", "tracking", "ZT123456789",
            "estimate", "已签收", "product", "定制刻字礼物（不可退）",
            "price", "399.00", "type", "定制商品", "orderDate", "2026-05-01",
            "deliveredDate", "2026-05-05"
        ));
        ORDERS.put("ORD-F001", Map.of(
            "status", "已签收", "carrier", "京东物流", "tracking", "JD987654321",
            "estimate", "已签收", "product", "进口生鲜礼盒",
            "price", "599.00", "type", "生鲜食品", "orderDate", "2026-05-09",
            "deliveredDate", "2026-05-11"
        ));
    }

    @Tool(description = "根据订单号查询订单状态，返回订单当前进度、商品、金额、物流单号和预计送达时间")
    public String queryOrder(
            @ToolParam(description = "订单号，如 ORD-2024001") String orderId) {
        log.info("[OrderTool] 查询订单: {}", orderId);
        Map<String, String> order = ORDERS.get(orderId);
        if (order == null) {
            return "未找到订单 " + orderId + "，请确认订单号是否正确。订单号格式为 ORD- 开头的 10 位编号。";
        }
        return String.format(
            "订单 %s\n商品：%s\n金额：¥%s\n状态：%s\n物流公司：%s\n运单号：%s\n预计送达：%s",
            orderId, order.get("product"), order.get("price"),
            order.get("status"), order.get("carrier"),
            order.get("tracking"), order.get("estimate"));
    }

    /**
     * 退款处理核心逻辑。
     * 依据法律法规：
     * - 《消费者权益保护法》第二十四条：商品不符合质量要求的，消费者可退货或要求更换、修理
     * - 《消费者权益保护法》第二十五条：七日无理由退货制度
     * - 《网络交易监督管理办法》第二十条：退款时限要求
     */
    @Tool(description = "发起退款申请。根据订单状态和退款原因自动判定退款类型和流程，并附法律依据")
    public String applyRefund(
            @ToolParam(description = "订单号") String orderId,
            @ToolParam(description = "退款原因，如：质量问题、不想要了、买错了、商品有瑕疵、发错货等") String reason) {
        log.info("[OrderTool] 退款: orderId={}, reason={}", orderId, reason);

        Map<String, String> order = ORDERS.get(orderId);
        if (order == null) {
            return "未找到订单 " + orderId + "，请确认订单号是否正确。";
        }

        String status = order.get("status");
        String productType = order.get("type");
        String productName = order.get("product");
        String price = order.get("price");
        String deliveredDate = order.get("deliveredDate");

        // 退款类型判定
        if ("待付款".equals(status)) {
            return buildRefundResponse(orderId, productName, price, "仅退款（未付款取消订单）",
                    "订单尚未支付，取消后不会产生任何费用。",
                    "《网络交易监督管理办法》第二十条：消费者在支付前取消订单的，经营者不得收取任何费用。",
                    "无需操作，即时生效");

        } else if ("待发货".equals(status)) {
            if (isQualityIssue(reason)) {
                return buildRefundResponse(orderId, productName, price, "仅退款（质量问题）",
                        "订单尚未发货，因质量问题申请退款，全额退款。",
                        "《消费者权益保护法》第二十四条：经营者提供的商品不符合质量要求的，消费者可以要求退货。",
                        "1-3 个工作日到账");
            } else {
                return buildRefundResponse(orderId, productName, price, "仅退款（未发货）",
                        "订单尚未发货，可以为您取消订单并全额退款。",
                        "《消费者权益保护法》第二十五条：消费者有权自付款之日起七日内无理由退货。订单未发货状态下适用。",
                        "1-3 个工作日到账");
            }

        } else if ("已发货".equals(status)) {
            if (isQualityIssue(reason)) {
                return buildRefundResponse(orderId, productName, price, "仅退款（质量问题）",
                        "商品已在途，因质量问题申请退款。建议您收到商品后拒收，或联系我们安排退回。运费由我们承担。",
                        "《消费者权益保护法》第二十四条：商品不符合质量要求的，消费者可以退货，经营者应当承担运输等必要费用。",
                        "仓库收到退货后 1-3 个工作日到账");
            } else {
                return buildRefundResponse(orderId, productName, price, "仅退款（七天无理由）",
                        "商品已在途。您可以在收到商品后拒收，或在签收后 7 天内申请无理由退货。请注意保持商品完好。",
                        "《消费者权益保护法》第二十五条：消费者有权自签收之日起七日内无理由退货。退回商品的运费由消费者承担。",
                        "仓库收到退货后 1-3 个工作日到账");
            }

        } else if ("已签收".equals(status)) {
            // 判断是否已超过 7 天
            boolean within7Days = isWithin7Days(deliveredDate);
            boolean isExcluded = isNonReturnable(productType);

            if (isExcluded) {
                return buildRefundResponse(orderId, productName, price, "不适用无理由退货",
                        "该商品属于「" + productType + "」，根据法律规定不适用七日无理由退货。" +
                        (isQualityIssue(reason) ? "如存在质量问题，请联系人工客服处理。" : ""),
                        "《消费者权益保护法》第二十五条：消费者定作的、鲜活易腐的、在线下载的数字化商品等不适用无理由退货。",
                        "请联系人工客服");

            } else if (!within7Days && !isQualityIssue(reason)) {
                return buildRefundResponse(orderId, productName, price, "超出七天无理由退货期限",
                        "您的订单已于 " + deliveredDate + " 签收，已超出 7 天无理由退货期限。" +
                        "如果您遇到的是质量问题，请选择质量问题退款，我们将为您处理。",
                        "《消费者权益保护法》第二十五条：七天无理由退货期限自签收之日起计算。",
                        "质量问题请联系人工客服");

            } else if (isQualityIssue(reason)) {
                return buildRefundResponse(orderId, productName, price, "退货退款（质量问题）",
                        "您签收后因质量问题申请退货。请将商品寄回指定退货地址，我们将在收到后处理退款。运费由我们承担。",
                        "《消费者权益保护法》第二十四条：商品不符合质量要求的，消费者可以退货，经营者应当承担运输等必要费用。\n《消费者权益保护法》第二十三条：经营者应当保证在正常使用商品的情况下其提供的商品应当具有的质量、性能、用途和有效期限。",
                        "仓库签收后 1-3 个工作日到账");

            } else {
                return buildRefundResponse(orderId, productName, price, "退货退款（七天无理由）",
                        "请将商品保持完好（含配件、包装），在 7 日内寄回指定退货地址。退回运费由您承担（如有运费险可获补偿）。我们收到后将在 3 个工作日内处理退款。",
                        "《消费者权益保护法》第二十五条：消费者有权自签收商品之日起七日内退货，且无需说明理由。退回商品的运费由消费者承担。",
                        "仓库签收后 1-3 个工作日到账");
            }

        } else if ("退款中".equals(status)) {
            return "订单 " + orderId + " 已处于退款处理中。当前退款正在处理，预计 1-3 个工作日到账。请耐心等待。";

        } else {
            return "订单 " + orderId + " 当前状态为「" + status + "」，暂不支持退款操作。如有疑问，请联系人工客服。";
        }
    }

    @Tool(description = "查询物流轨迹，需要快递单号。返回最新的物流流转信息")
    public String trackLogistics(
            @ToolParam(description = "快递单号") String trackingNumber) {
        log.info("[OrderTool] 查物流: {}", trackingNumber);
        if (trackingNumber == null || trackingNumber.isBlank()) {
            return "请提供快递单号。";
        }
        return String.format(
            "快递单号 %s 最新轨迹：\n  2026-05-15 08:00  到达 北京分拨中心\n  2026-05-14 22:00  离开 杭州分拨中心\n  2026-05-14 18:00  已揽收\n\n预计明天送达，请保持电话畅通。如需进一步帮助，请联系快递公司客服。",
            trackingNumber);
    }

    // ==================== 私有方法 ====================

    /**
     * 判断退款原因是否属于质量问题。
     */
    private boolean isQualityIssue(String reason) {
        if (reason == null) return false;
        String r = reason.toLowerCase();
        return r.contains("质量") || r.contains("故障") || r.contains("坏了") || r.contains("破损")
            || r.contains("瑕疵") || r.contains("发错") || r.contains("错发") || r.contains("漏发")
            || r.contains("无法使用") || r.contains("不工作") || r.contains("损坏")
            || r.contains("描述不符") || r.contains("与描述不符") || r.contains("不同");
    }

    /**
     * 判断商品是否属于不适用无理由退货的类型。
     */
    private boolean isNonReturnable(String productType) {
        if (productType == null) return false;
        return productType.contains("定制") || productType.contains("生鲜")
            || productType.contains("食品") || productType.contains("虚拟");
    }

    /**
     * 判断是否在 7 天内。
     */
    private boolean isWithin7Days(String deliveredDate) {
        if (deliveredDate == null || deliveredDate.isEmpty()) return true;
        try {
            LocalDate delivered = LocalDate.parse(deliveredDate, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate now = LocalDate.now();
            long days = java.time.temporal.ChronoUnit.DAYS.between(delivered, now);
            return days <= 7;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 构建结构化的退款回复，含法律依据。
     */
    private String buildRefundResponse(String orderId, String product, String price,
                                        String refundType, String process,
                                        String legalBasis, String arrivalTime) {
        return String.format(
                """
                        ═══════════════════════════════════════
                        📋 退款受理确认
                        ═══════════════════════════════════════
                        
                        订单：%s
                        商品：%s
                        金额：¥%s
                        退款类型：%s
                        
                        📝 处理说明
                        %s
                        
                        ⚖️ 法律依据
                        %s
                        
                        💰 到账时间
                        %s
                        
                        如需取消退款申请或了解进度，请随时联系我们。""",
            orderId, product, price, refundType, process, legalBasis, arrivalTime);
    }
}
