package com.example.smartassistant.service.core;

import com.example.smartassistant.common.agent.protocol.*;
import com.example.smartassistant.common.quality.DomainQualityResult;
import java.util.*;

/** Read-only input preparation owned by Order. Never calls a mutation or accepts a price. */
public final class OrderClarificationService {
    private OrderClarificationService() { }
    private static final OrderClarificationSchema SCHEMA = OrderClarificationSchema.defaultSchema();

    public static AgentExecutionResponse prepare(String operation, Map<String, Object> input) {
        return prepare(operation, input, null, null);
    }

    public static AgentExecutionResponse prepare(String operation, Map<String, Object> input,
                                                 String userId, OrderCheckoutHistoryService history) {
        List<String> required = SCHEMA.required(operation);
        if (required == null) return AgentExecutionResponse.success(
                "请说明您要办理的订单业务，我会先核实所需信息；本次没有修改订单。",
                DomainQualityResult.pass(1, "ORDER_PREPARATION_GUIDANCE"));
        Map<String, Object> provided = input == null ? Map.of() : input;
        String historyWarning = historyWarning(operation, provided, userId, history);
        List<String> missing = required.stream().filter(key -> SCHEMA.aliases(key).stream()
                .noneMatch(alias -> usable(key, provided.get(alias)))).toList();
        if (missing.isEmpty()) return AgentExecutionResponse.success(
                "所需资料已收到，接下来会核实商品价格或订单归属、状态，并在操作前请您确认；本次没有修改订单。"
                        + historyWarning,
                Map.of("operation", operation),
                DomainQualityResult.pass(1, "ORDER_PREPARATION_GUIDANCE"));
        var clarification = new ClarificationRequest("order", operation, missing);
        return AgentExecutionResponse.success("为了继续办理，请补充"
                + String.join("、", missing.stream().map(SCHEMA::label).toList())
                + "。" + (missing.contains("shippingAddress")
                    ? "收货地址请具体到区县、街道和门牌。" : "")
                + ("CREATE_ORDER".equals(operation) ? "商品价格会由系统核实。" : "")
                + "补充资料不会直接提交订单操作，核实后会再请您确认。" + historyWarning,
                Map.of(ClarificationRequest.DATA_KEY, clarification.toMap(), "clarificationRequired", true),
                DomainQualityResult.pass(1, "ORDER_INPUT_CLARIFICATION"));
    }

    private static String historyWarning(String operation, Map<String, Object> input,
                                         String userId, OrderCheckoutHistoryService history) {
        if (!"CREATE_ORDER".equals(operation) || history == null || userId == null) return "";
        try {
            List<String> conflicts = history.conflicts(Long.parseLong(userId), input);
            if (conflicts.isEmpty()) return "";
            return "本次填写的" + String.join("、", conflicts.stream().map(SCHEMA::label).toList())
                    + "与单笔历史订单资料不一致，请核对；旧资料不会自动覆盖本次填写。";
        } catch (NumberFormatException invalidOwner) {
            return "";
        }
    }

    private static boolean usable(String key, Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) return false;
        return switch (key) {
            case "orderNumber" -> text.matches("(?:ORD|BULK)-[A-Za-z0-9-]{1,64}");
            case "recipientPhone" -> text.matches("1[3-9][0-9]{9}");
            case "recipientName" -> text.length() <= 40 && text.chars().noneMatch(Character::isISOControl);
            case "shippingAddress" -> OrderShippingAddressPolicy.usable(text);
            case "afterSalesType" -> SCHEMA.allowedAfterSalesType(text);
            default -> text.length() <= 200 && text.chars().noneMatch(Character::isISOControl);
        };
    }
}
