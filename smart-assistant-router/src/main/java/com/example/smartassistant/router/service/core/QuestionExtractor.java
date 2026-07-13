/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 问题参数提取工具类。
 * <p>
 * 从 RouterService 中拆出的纯函数工具，包含问题参数提取、截断、原始问题还原等
 * 不依赖任何 RouterService 内部状态的方法。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-13
 */
public final class QuestionExtractor {

    private QuestionExtractor() {}

    // ==================== 订单参数提取 ====================

    /** 订单号正则：ORD-123, ORD_123, ORD123 */
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("ORD[-_]?\\d+");

    /** 商品关键词指示词 */
    private static final String[] PRODUCT_INDICATORS = {"多少钱", "价格", "有货", "库存", "怎么样", "好不好"};

    /** 地点指示词 */
    private static final String[] LOCATION_INDICATORS = {"在", "到", "去", "于", "的天气", "气温"};

    /** 当前问题标记 */
    private static final String CURRENT_QUESTION_MARKER = "【当前问题】";

    // ==================== 公开 API ====================

    /**
     * ⭐ 截断字符串（用于日志）
     */
    public static String truncate(String str, int maxLength) {
        if (str == null) return "";
        return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
    }

    /**
     * ⭐ 从完整 Prompt 中提取原始问题。
     * <p>
     * Router 接收到的 question 可能包含【用户历史信息】【用户画像】【当前问题】等模板标记。
     * 提取【当前问题】后的文本作为缓存使用的原始问题，确保同一问题的 MD5 一致。
     * </p>
     */
    public static String extractRawQuestion(String question) {
        if (question == null || question.isBlank()) return question;
        int currentQuestionIdx = question.indexOf(CURRENT_QUESTION_MARKER);
        if (currentQuestionIdx >= 0) {
            String after = question.substring(currentQuestionIdx + CURRENT_QUESTION_MARKER.length()).trim();
            return after.replaceAll("^[\\s\\n\\r]+", "").trim();
        }
        return question.trim();
    }

    // ==================== 会话历史提取 ====================

    /**
     * 从对话历史中提取最近一条用户问题。
     * 历史格式：["用户：...", "助手：...", "用户：..."]
     */
    public static String extractLastUserQuestion(List<String> history) {
        if (history == null || history.isEmpty()) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            String msg = history.get(i);
            if (msg.startsWith("用户：") || msg.startsWith("用户:")) {
                return msg.substring(3).trim();
            }
        }
        return null;
    }

    // ==================== 订单参数提取 ====================

    /**
     * 从问题中提取订单 ID（格式：ORD-XXX, ORD_XXX, ORDXXX）
     */
    public static String extractOrderId(String question) {
        if (question == null) return "";
        var matcher = ORDER_ID_PATTERN.matcher(question);
        return matcher.find() ? matcher.group() : question;
    }

    /**
     * 从问题中提取订单参数字符串（JSON 格式）
     */
    public static String extractOrderParams(String question) {
        String orderId = extractOrderId(question);
        if (!orderId.isEmpty() && !orderId.equals(question)) {
            return "{\"orderId\": \"" + orderId + "\"}";
        }
        return "{\"orderId\": \"" + question + "\"}";
    }

    // ==================== 商品参数提取 ====================

    /**
     * 从问题中提取商品名称
     */
    public static String extractProductName(String question) {
        if (question == null) return "";
        for (String indicator : PRODUCT_INDICATORS) {
            int idx = question.indexOf(indicator);
            if (idx > 0) {
                return question.substring(0, idx).trim();
            }
        }
        if (question.length() > 8) return question.substring(0, Math.min(question.length(), 20));
        return question;
    }

    // ==================== 地点参数提取 ====================

    /**
     * 从问题中提取地点
     */
    public static String extractLocation(String question) {
        if (question == null) return "";
        for (String ind : LOCATION_INDICATORS) {
            int idx = question.indexOf(ind);
            if (idx >= 0) {
                String before = question.substring(0, idx).trim();
                String after = question.substring(idx + ind.length()).trim();
                if (before.length() >= 2 && before.length() <= 10) return before;
                if (after.length() >= 2 && after.length() <= 10 && !after.contains("?")) return after;
            }
        }
        return question.replace("天气", "").replace("气温", "").replace("?", "").trim();
    }
}
