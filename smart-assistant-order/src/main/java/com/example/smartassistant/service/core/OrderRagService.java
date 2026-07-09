/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.core;

import com.example.smartassistant.common.rag.RetrievalQualityResult;
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
 * <p>
 * P2 改进：返回 {@link RetrievalQualityResult} 含归一化质量分数和结构化拒答，
 * 未找到数据时返回明确的"无法回答"拒绝消息而非静默空字符串。
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
     * P1 带质量标志的 RAG 检索（兼容旧调用）。
     *
     * @deprecated 请使用 {@link #retrieveWithQualityResult(IntentType, String)}
     */
    @Deprecated
    public RetrievalResult retrieveWithQuality(IntentType intent, String message) {
        RetrievalQualityResult qr = retrieveWithQualityResult(intent, message);
        return new RetrievalResult(qr.getContent(), qr.isHighQuality());
    }

    /**
     * 带结构化质量评估的 RAG 检索——返回 {@link RetrievalQualityResult} 共享模型。
     *
     * @param intent  检测到的意图
     * @param message 原始用户消息
     * @return 结构化检索质量结果（含归一化分数和明确拒答消息）
     */
    public RetrievalQualityResult retrieveWithQualityResult(IntentType intent, String message) {
        if (intent == null || message == null) {
            return RetrievalQualityResult.noData("无效查询");
        }

        return switch (intent) {
            case QUERY_ORDER -> retrieveForQualityResult(message, "订单");
            case REFUND -> retrieveForQualityResult(message, "退款");
            case CREATE_ORDER -> RetrievalQualityResult.highQuality(
                    "【下单引导】请让用户提供以下信息：\n• 商品名称\n• 购买数量\n• 收货人姓名、电话、地址\n• 支付方式\n确认信息后调用 createOrder 创建订单。",
                    1.0);
            case CANCEL -> retrieveForQualityResult(message, "取消");
            default -> RetrievalQualityResult.insufficientEvidence("", 0.0,
                    "无法识别该消息的订单意图类型。");
        };
    }

    /**
     * 根据意图类型预检索数据（兼容旧调用，无质量标志）。
     *
     * @param intent  检测到的意图
     * @param message 原始用户消息
     * @return 预检索到的上下文文本（可能为空）
     */
    public String retrieve(IntentType intent, String message) {
        RetrievalResult result = retrieveWithQuality(intent, message);
        return result.content();
    }

    /**
     * 构建注入上下文后的增强消息。
     * 将预检索结果附加到用户消息前，Agent 可直接使用。
     */
    public String buildEnhancedMessage(IntentType intent, String originalMessage) {
        RetrievalQualityResult qr = retrieveWithQualityResult(intent, originalMessage);
        if (qr.isRejected()) {
            // 无数据时直接返回拒绝消息，不走 Agent 增强
            return originalMessage;
        }
        String context = qr.getContent();
        if (context.isBlank()) {
            return originalMessage;
        }
        return "[系统已检索到以下信息]\n" + context + "\n\n[请基于以上信息，结合工具查询结果回答用户]\n用户问题：" + originalMessage;
    }

    // ═══════════════════════════════════════════════════════════
    // 通用预检索逻辑（按意图类型区分分数和拒答消息）
    // ═══════════════════════════════════════════════════════════

    /**
     * 通用订单预检索逻辑，根据意图类型返回不同分数和拒绝消息。
     */
    private RetrievalQualityResult retrieveForQualityResult(String message, String intentLabel) {
        String orderId = extractOrderId(message);
        if (orderId == null) {
            return RetrievalQualityResult.insufficientEvidence(
                    "", 0.0,
                    "请提供订单号（格式：ORD-xxx）以便查询" + intentLabel + "信息。");
        }

        try {
            String orderInfo = orderTools.queryOrder(orderId);
            boolean foundOrder = orderInfo != null && !orderInfo.contains("ORDER_NOT_FOUND");

            if (!foundOrder) {
                return RetrievalQualityResult.insufficientEvidence(
                        "", 0.0,
                        "系统中未找到订单号「" + orderId + "」，请核对后重试。");
            }

            // 找到订单：根据不同意图附加额外信息
            StringBuilder sb = new StringBuilder();
            sb.append("【订单信息】").append(orderInfo);

            boolean hasLogistics = false;
            if ("订单".equals(intentLabel)) {
                try {
                    String logistics = orderTools.trackLogistics(orderId);
                    if (logistics != null && !logistics.contains("ORDER_NOT_FOUND")
                            && !logistics.contains("暂无物流")) {
                        sb.append("\n【物流信息】").append(logistics);
                        hasLogistics = true;
                    }
                } catch (Exception e) {
                    log.debug("[OrderRAG] 物流查询失败（可忽略）: {}", e.getMessage());
                }
            }

            // 附加规则提示
            switch (intentLabel) {
                case "退款" -> sb.append("\n\n【退款规则】\n• 仅已发货/已签收的订单可申请退款\n• 退款需二次确认\n• 退款金额以实际支付金额为准");
                case "取消" -> sb.append("\n\n【取消规则】仅「待付款」和「待发货」状态的订单可取消");
                default -> {} // 查询无需额外规则
            }

            // 质量分数：有物流信息最高分
            double qualityScore = hasLogistics ? 0.95 : 0.85;
            log.info("[OrderRAG] {}意图预检索成功: orderId={}, qualityScore={}",
                    intentLabel, orderId, String.format("%.2f", qualityScore));

            return RetrievalQualityResult.highQuality(sb.toString().trim(), qualityScore);

        } catch (Exception e) {
            log.warn("[OrderRAG] {}意图预检索失败: {}", intentLabel, e.getMessage());
            return RetrievalQualityResult.insufficientEvidence(
                    "", 0.3,
                    "查询订单「" + orderId + "」时出现系统错误，请稍后重试。");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 兼容旧调用的 Record
    // ═══════════════════════════════════════════════════════════

    /** P1 RAG 检索结果（含质量标志）—— 兼容旧调用 */
    public record RetrievalResult(String content, boolean foundData) {}

    // ═══════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 从消息中提取订单号（ORD-xxx 格式）。
     */
    private String extractOrderId(String message) {
        if (message == null) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("ORD-\\w+");
        java.util.regex.Matcher matcher = pattern.matcher(message);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
}
