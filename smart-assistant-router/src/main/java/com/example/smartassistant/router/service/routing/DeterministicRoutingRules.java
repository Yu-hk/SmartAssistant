package com.example.smartassistant.router.service.routing;

import com.example.smartassistant.router.model.TaskAnalysisResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Deterministic routing rules that must not depend on model availability. */
public final class DeterministicRoutingRules {

    private static final Pattern NEGATED_ORDER_ACTION = Pattern.compile(
            "(?:不是要|不要|不再|不会|无需|不用|禁止|不允许|请勿|不能|不可|不)"
                    + "(?:再|直接|实际)?(?:执行|进行)?"
                    + "(?:下单|购买|买下|创建订单)");

    private DeterministicRoutingRules() {
    }

    public static TaskAnalysisResult productThenOrder(String question) {
        if (question == null || question.isBlank()) return null;

        String normalized = question.toLowerCase(Locale.CHINESE);
        boolean productDiscovery = containsAny(normalized, List.of("商品", "产品"))
                && containsAny(normalized, List.of("热门", "热销", "排行", "榜单", "销量"));
        boolean existingOrderQuery = containsAny(normalized, List.of(
                "查询订单", "查订单", "我的订单", "最近的订单", "历史订单",
                "订单状态", "订单进度", "订单详情", "订单号", "物流", "退款进度"));
        boolean orderPreparation = containsAny(normalized, List.of(
                "下单资料", "下单材料", "下单信息", "下单还缺", "下单需要",
                "说明下单", "告诉我下单", "如何下单", "怎么下单"));

        if (!productDiscovery || (!orderPreparation && !containsNonNegatedOrderAction(normalized))
                || existingOrderQuery) {
            return null;
        }

        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setIntentCategory("COMPLEX");
        analysis.setConfidence(0.99);
        analysis.setTaskGoal("查询热门商品，并在用户选定商品、补齐收货信息后再处理下单");
        analysis.setSubIntents(List.of(
                Map.of("intent", "PRODUCT_QUERY", "description", "查询当前热门商品列表", "order", 1),
                Map.of("intent", "CREATE_ORDER", "description", "说明下单所需资料并等待用户选择商品", "order", 2)));
        analysis.setActionConstraints(actionConstraints(normalized));
        analysis.setNeedsClarification(true);
        analysis.setMissingSlots(List.of(
                "productName", "amount", "contactName", "contactPhone", "shippingAddress"));
        analysis.setClarificationReason("尚未选定具体商品，且收货信息不完整");
        analysis.setClarificationQuestions(List.of(
                "请从热门商品中选择一款，并提供收货人姓名、联系电话和收货地址"));
        return analysis;
    }

    public static String agentForCategory(String category) {
        if (category == null) return null;
        String normalized = category.trim().toUpperCase(Locale.ROOT);
        String exact = switch (normalized) {
            case "ORDER" -> "order";
            case "PRODUCT" -> "product";
            case "GENERAL" -> "general";
            default -> null;
        };
        if (exact != null) return exact;

        if (containsAny(normalized, List.of("ORDER", "REFUND", "LOGISTICS", "AFTER_SALES"))
                || containsAny(category, List.of("订单", "退款", "退货", "物流", "售后"))) {
            return "order";
        }
        if (normalized.contains("PRODUCT") || containsAny(category, List.of("商品", "产品", "推荐"))) {
            return "product";
        }
        if (normalized.contains("GENERAL") || containsAny(category, List.of("通用", "天气", "搜索"))) {
            return "general";
        }
        return null;
    }

    private static List<String> actionConstraints(String normalized) {
        List<String> constraints = new ArrayList<>();
        boolean readOnly = containsAny(normalized, List.of("只查询", "仅查询", "只做查询", "仅做查询"));
        if (readOnly || containsAny(normalized, List.of("不要创建", "禁止创建"))) {
            constraints.add("仅查询和说明，不创建订单");
        }
        if (readOnly || containsAny(normalized, List.of("不要支付", "禁止支付"))) {
            constraints.add("不执行支付");
        }
        if (readOnly || containsAny(normalized, List.of("不要退款", "禁止退款"))) {
            constraints.add("不执行退款");
        }
        if (readOnly || containsAny(normalized, List.of("不要取消", "禁止取消"))) {
            constraints.add("不执行取消");
        }
        return constraints;
    }

    private static boolean containsNonNegatedOrderAction(String question) {
        String withoutNegatedActions = NEGATED_ORDER_ACTION.matcher(question.replaceAll("\\s+", ""))
                .replaceAll("");
        return containsAny(withoutNegatedActions, List.of("下单", "购买", "买下", "创建订单"));
    }

    private static boolean containsAny(String value, List<String> markers) {
        return markers.stream().anyMatch(value::contains);
    }
}
