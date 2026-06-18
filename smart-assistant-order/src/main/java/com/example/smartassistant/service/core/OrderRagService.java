/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.core;

import com.example.smartassistant.service.core.OrderIntentService.IntentType;
import com.example.smartassistant.tools.OrderTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * ⭐ 订单 RAG 检索服务。
 * <p>
 * 根据 {@link IntentType} 预检索相关数据，为 Agent 提供上下文。
 * 检索结果注入到用户消息中，Agent 在 ReAct 循环中可直接使用。
 * </p>
 */
@Service
public class OrderRagService {

    private static final Logger log = LoggerFactory.getLogger(OrderRagService.class);

    private final OrderTools orderTools;

    public OrderRagService(OrderTools orderTools) {
        this.orderTools = orderTools;
    }

    /**
     * 根据意图类型预检索数据。
     *
     * @param intent  检测到的意图
     * @param message 原始用户消息
     * @return 预检索到的上下文文本（可能为空）
     */
    public String retrieve(IntentType intent, String message) {
        if (intent == null || message == null) return "";

        switch (intent) {
            case QUERY_ORDER:
                return retrieveForQuery(message);
            case REFUND:
                return retrieveForRefund(message);
            case CREATE_ORDER:
                return retrieveForCreate(message);
            case CANCEL:
                return retrieveForCancel(message);
            default:
                return "";
        }
    }

    /**
     * 构建注入上下文后的增强消息。
     * 将预检索结果附加到用户消息前，Agent 可直接使用。
     */
    public String buildEnhancedMessage(IntentType intent, String originalMessage) {
        String context = retrieve(intent, originalMessage);
        if (context.isBlank()) {
            return originalMessage;
        }
        return "[系统已检索到以下信息]\n" + context + "\n\n[请基于以上信息，结合工具查询结果回答用户]\n用户问题：" + originalMessage;
    }

    // ==================== 各意图的检索策略 ====================

    /** 查询订单意图：尝试提取订单号并预查 */
    private String retrieveForQuery(String message) {
        String orderId = extractOrderId(message);
        if (orderId != null) {
            try {
                // 预查订单信息
                String orderInfo = orderTools.queryOrder(orderId);
                String logistics = orderTools.trackLogistics(orderId);
                StringBuilder sb = new StringBuilder();
                if (orderInfo != null && !orderInfo.contains("ORDER_NOT_FOUND")) {
                    sb.append("【订单信息】").append(orderInfo).append("\n");
                }
                if (logistics != null && !logistics.contains("ORDER_NOT_FOUND") && !logistics.contains("暂无物流")) {
                    sb.append("【物流信息】").append(logistics);
                }
                if (sb.length() > 0) {
                    log.info("[OrderRAG] 查询意图预检索成功: orderId={}", orderId);
                    return sb.toString().trim();
                }
            } catch (Exception e) {
                log.warn("[OrderRAG] 查询意图预检索失败: {}", e.getMessage());
            }
        }
        return "";
    }

    /** 退款意图：预查退款相关规则 */
    private String retrieveForRefund(String message) {
        String orderId = extractOrderId(message);
        if (orderId != null) {
            try {
                String orderInfo = orderTools.queryOrder(orderId);
                if (orderInfo != null && !orderInfo.contains("ORDER_NOT_FOUND")) {
                    return "【订单信息】" + orderInfo + "\n\n【退款规则】\n• 仅已发货/已签收的订单可申请退款\n• 退款需二次确认\n• 退款金额以实际支付金额为准";
                }
            } catch (Exception e) {
                log.warn("[OrderRAG] 退款意图预检索失败: {}", e.getMessage());
            }
        }
        return "【退款提示】请提供订单号以便查询退款信息。";
    }

    /** 下单意图：引导提示 */
    private String retrieveForCreate(String message) {
        return "【下单引导】请让用户提供以下信息：\n• 商品名称\n• 购买数量\n• 收货人姓名、电话、地址\n• 支付方式\n确认信息后调用 createOrder 创建订单。";
    }

    /** 取消意图：预查订单状态 */
    private String retrieveForCancel(String message) {
        String orderId = extractOrderId(message);
        if (orderId != null) {
            try {
                String orderInfo = orderTools.queryOrder(orderId);
                if (orderInfo != null && !orderInfo.contains("ORDER_NOT_FOUND")) {
                    return "【订单信息】" + orderInfo + "\n\n【取消规则】仅「待付款」和「待发货」状态的订单可取消";
                }
            } catch (Exception e) {
                log.warn("[OrderRAG] 取消意图预检索失败: {}", e.getMessage());
            }
        }
        return "";
    }

    /**
     * 从消息中提取订单号（ORD-xxx 格式）。
     */
    private String extractOrderId(String message) {
        if (message == null) return null;
        // 匹配 ORD-xxx 格式
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("ORD-\\w+");
        java.util.regex.Matcher matcher = pattern.matcher(message);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
}
