package com.example.smartassistant.service.core;

import com.example.smartassistant.common.agent.protocol.*;
import com.example.smartassistant.common.quality.DomainQualityResult;
import java.util.*;

/** Read-only input preparation owned by Order. Never calls a mutation or accepts a price. */
public final class OrderClarificationService {
    private OrderClarificationService() { }
    private static final Map<String, List<String>> REQUIRED = Map.of(
            "CREATE_ORDER", List.of("product", "recipientName", "recipientPhone", "shippingAddress"),
            "TRACK_LOGISTICS", List.of("orderNumber"),
            "QUERY_PAYMENT_PENDING", List.of("orderNumber"),
            "CANCEL_ORDER", List.of("orderNumber", "reason"),
            "REFUND_ORDER", List.of("orderNumber", "reason"),
            "APPLY_AFTER_SALES", List.of("orderNumber", "afterSalesType", "reason"));
    private static final Map<String, List<String>> ALIASES = Map.of(
            "product", List.of("product", "product_name", "productName"),
            "recipientName", List.of("recipientName", "recipient_name", "contact_name", "contactName"),
            "recipientPhone", List.of("recipientPhone", "recipient_phone", "contact_phone", "contactPhone"),
            "shippingAddress", List.of("shippingAddress", "shipping_address", "address"),
            "orderNumber", List.of("orderNumber", "order_id", "orderId", "order_number"),
            "reason", List.of("reason", "cancel_reason", "refund_reason", "after_sales_reason"),
            "afterSalesType", List.of("afterSalesType", "after_sales_type", "request_type"));
    private static final Map<String, String> LABELS = Map.of(
            "product", "具体商品", "recipientName", "收货人姓名", "recipientPhone", "联系电话",
            "shippingAddress", "收货地址", "orderNumber", "订单号", "reason", "具体原因", "afterSalesType", "售后类型");

    public static AgentExecutionResponse prepare(String operation, Map<String, Object> input) {
        List<String> required = REQUIRED.get(operation);
        if (required == null) return AgentExecutionResponse.success(
                "请说明您要办理的订单业务，我会先核实所需信息；本次没有修改订单。",
                DomainQualityResult.pass(1, "ORDER_PREPARATION_GUIDANCE"));
        List<String> missing = required.stream().filter(key -> ALIASES.get(key).stream()
                .noneMatch(alias -> usable(key, input.get(alias)))).toList();
        if (missing.isEmpty()) return AgentExecutionResponse.success(
                "所需资料已收到，接下来会核实商品价格或订单归属、状态，并在操作前请您确认；本次没有修改订单。",
                DomainQualityResult.pass(1, "ORDER_PREPARATION_GUIDANCE"));
        var clarification = new ClarificationRequest("order", operation, missing);
        return AgentExecutionResponse.success("为了继续办理，请补充"
                + String.join("、", missing.stream().map(LABELS::get).toList())
                + "。" + ("CREATE_ORDER".equals(operation) ? "商品价格会由系统核实。" : "")
                + "补充资料不会直接提交订单操作，核实后会再请您确认。",
                Map.of(ClarificationRequest.DATA_KEY, clarification.toMap(), "clarificationRequired", true),
                DomainQualityResult.pass(1, "ORDER_INPUT_CLARIFICATION"));
    }

    private static boolean usable(String key, Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) return false;
        return switch (key) {
            case "orderNumber" -> text.matches("(?:ORD|BULK)-[A-Za-z0-9-]{1,64}");
            case "recipientPhone" -> text.matches("1[3-9][0-9]{9}");
            case "recipientName" -> text.length() <= 40 && text.chars().noneMatch(Character::isISOControl);
            case "shippingAddress" -> text.length() >= 6 && text.length() <= 200 && text.chars().noneMatch(Character::isISOControl);
            case "afterSalesType" -> Set.of("退货", "换货", "维修").contains(text);
            default -> text.length() <= 200 && text.chars().noneMatch(Character::isISOControl);
        };
    }
}
