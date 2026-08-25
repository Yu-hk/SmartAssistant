/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.core;

import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import com.example.smartassistant.common.rag.KnowledgeSeedData;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.LogisticsDTO;
import com.example.smartassistant.common.tool.spi.dto.OrderDTO;
import com.example.smartassistant.service.core.OrderIntentService.IntentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

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

    private final OrderDataProvider orderData;
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    public OrderRagService(OrderDataProvider orderData) {
        this(orderData, null);
    }

    @Autowired
    public OrderRagService(
            OrderDataProvider orderData,
            @Qualifier("orderKnowledgeRetrievalService") KnowledgeRetrievalService knowledgeRetrievalService) {
        this.orderData = orderData;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
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
            case REFUND_POLICY -> retrieveRefundPolicy(message);
            case ORDER_PREPARATION_GUIDANCE -> retrieveOrderPreparationGuidance();
            case ORDER_GUIDANCE -> retrieveOrderGuidance();
            case REFUND -> retrieveForQualityResult(message, "退款");
            case PAY -> retrieveForQualityResult(message, "支付");
            case SHIP -> retrieveForQualityResult(message, "发货");
            case TRACK_LOGISTICS -> retrieveForQualityResult(message, "订单");
            case CONFIRM_DELIVERY -> retrieveForQualityResult(message, "确认收货");
            case CREATE_ORDER -> RetrievalQualityResult.highQuality(
                    "【下单引导】请让用户提供以下信息：\n• 商品名称\n• 购买数量\n• 收货人姓名、电话、地址\n• 支付方式\n确认信息后调用 createOrder 创建订单。",
                    1.0);
            case CANCEL -> retrieveForQualityResult(message, "取消");
            default -> RetrievalQualityResult.insufficientEvidence("", 0.0,
                    "无法识别该消息的订单意图类型。");
        };
    }

    /**
     * 退款政策属于公共知识，不要求用户先提供订单号。
     * 优先查询运行时订单知识库；检索设施不可用时回退到同一份官方种子文档，
     * 避免把基础政策咨询错误地降级成订单参数澄清。
     */
    private RetrievalQualityResult retrieveRefundPolicy(String message) {
        String content = null;
        if (knowledgeRetrievalService != null) {
            try {
                content = knowledgeRetrievalService.search(KnowledgeSeedData.ORDER_KB, message, 3);
                if (content != null && (content.startsWith("INSUFFICIENT_EVIDENCE")
                        || content.contains("不存在"))) {
                    content = null;
                }
            } catch (Exception e) {
                log.warn("[OrderRAG] 退款政策知识库检索失败，使用官方种子文档: {}", e.getMessage());
            }
        }

        if (content == null || content.isBlank()) {
            content = KnowledgeSeedData.orderDocuments().stream()
                    .filter(document -> "退款政策".equals(document.getCategory()))
                    .map(OrderRagService::formatKnowledgeDocument)
                    .collect(Collectors.joining("\n\n"));
        }

        if (content.isBlank()) {
            return RetrievalQualityResult.insufficientEvidence(
                    "", 0.0, "当前退款政策知识暂不可用，请稍后重试。");
        }
        return RetrievalQualityResult.highQuality("【退款与退货政策】\n" + content, 0.95);
    }

    private static String formatKnowledgeDocument(KnowledgeDocument document) {
        return "【" + document.getTitle() + "】\n" + document.getContent();
    }

    /**
     * Public policy questions are read-only knowledge lookups. Return the retrieved source text
     * directly instead of asking the ReAct loop to decide whether an order action needs approval.
     */
    public String buildRefundPolicyAnswer(RetrievalQualityResult result) {
        if (result == null || result.isRejected()
                || result.getContent() == null || result.getContent().isBlank()) {
            return "当前退货退款政策暂不可用，请稍后重试。";
        }
        String content = result.getContent().trim();
        if (content.startsWith("【退款与退货政策】")) {
            content = content.substring("【退款与退货政策】".length()).stripLeading();
        }
        return "根据当前退货退款政策：\n" + content;
    }

    /**
     * 返回不依赖具体订单号的只读订单生命周期指南。这里不调用执行型工具，
     * 也不把“如何操作”误解释成“现在替用户操作”。
     */
    private RetrievalQualityResult retrieveOrderGuidance() {
        return RetrievalQualityResult.highQuality("""
                【订单生命周期操作说明】
                1. 查询订单：登录当前账号后进入订单记录，选择目标订单查看状态、金额和物流；需要精确查询时提供订单号。
                2. 取消订单：先确认订单号和当前状态。通常仅待付款或待发货订单可取消，实际是否可取消以订单实时状态为准；执行前必须再次确认。
                3. 申请售后：准备订单号、问题描述、售后类型及必要凭证；退款或退货条件、处理时效以当前售后政策和订单状态为准，提交前必须再次确认。
                4. 查看退款进度：进入目标订单的售后/退款记录查看审核、退货、退款处理中或退款完成等状态；需要精确查询时提供订单号或售后单号，到账时间以支付渠道实际处理为准。
                5. 安全边界：仅咨询流程不会创建、取消或退款任何订单，也不会补造地址、金额、支付信息或订单号。""", 1.0);
    }

    private RetrievalQualityResult retrieveOrderPreparationGuidance() {
        return RetrievalQualityResult.highQuality("""
                【下单前信息清单】
                1. 商品：具体商品、规格/型号、数量，以及可核验的成交价格。
                2. 收货：收货人姓名、联系电话、完整收货地址。
                3. 交易：支付方式；如需发票，再确认发票类型与抬头信息。
                4. 最终确认：提交前再次核对商品、金额、库存和收货信息。
                5. 安全边界：当前仅说明所需信息，不会执行下单，也不会创建测试订单；不得替用户补造缺失参数。""", 1.0);
    }

    public String buildOrderGuidanceAnswer(RetrievalQualityResult result) {
        if (result == null || result.isRejected()
                || result.getContent() == null || result.getContent().isBlank()) {
            return "当前订单操作指南暂不可用，请稍后重试。";
        }
        return result.getContent().trim();
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
     *
     * @deprecated 调用方应先用 {@link #retrieveWithQualityResult(IntentType, String)} 取质量结果，
     *             在 {@code isRejected()} 时短路拒答（不调用 LLM），否则再用本方法格式化。
     *             请改用 {@link #buildEnhancedMessage(RetrievalQualityResult, String)}。
     */
    @Deprecated
    public String buildEnhancedMessage(IntentType intent, String originalMessage) {
        return buildEnhancedMessage(retrieveWithQualityResult(intent, originalMessage), originalMessage);
    }

    /**
     * ⭐ P1 无证据拒答感知版：基于已获取的 {@link RetrievalQualityResult} 构建增强消息。
     * <ul>
     *   <li>{@code isRejected()} 或内容为空 → 返回原消息（由调用方决定短路拒答）</li>
     *   <li>否则 → 在用户问题前附上检索到的上下文</li>
     * </ul>
     *
     * @param qr             已获取的检索质量结果（不可为 null 由调用方保证）
     * @param originalMessage 原始用户消息
     * @return 增强后的消息；无可注入上下文时返回原消息
     */
    public String buildEnhancedMessage(RetrievalQualityResult qr, String originalMessage) {
        if (qr == null || qr.isRejected() || qr.getContent() == null || qr.getContent().isBlank()) {
            return originalMessage;
        }
        return "[系统已检索到以下信息]\n" + qr.getContent()
                + "\n\n[请基于以上信息，结合工具查询结果回答用户]\n用户问题：" + originalMessage;
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
                    "请提供订单号（如 ORD-xxx 或 BULK-xxx）以便查询" + intentLabel + "信息。");
        }

        try {
            OrderDTO order = orderData.findOrderByOrderId(orderId);
            boolean foundOrder = order != null;

            if (!foundOrder) {
                return RetrievalQualityResult.insufficientEvidence(
                        "", 0.0,
                        "系统中未找到订单号「" + orderId + "」，请核对后重试。");
            }

            // 找到订单：根据不同意图附加额外信息
            StringBuilder sb = new StringBuilder();
            sb.append("【订单信息】").append(formatOrder(order));

            boolean hasLogistics = false;
            if ("订单".equals(intentLabel)) {
                try {
                    LogisticsDTO logistics = orderData.findLogisticsByOrderId(orderId);
                    if (logistics != null && logistics.getLogisticsDetail() != null
                            && !logistics.getLogisticsDetail().isBlank()
                            && !"[]".equals(logistics.getLogisticsDetail())) {
                        sb.append("\n【物流信息】").append(formatLogistics(logistics));
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
                case "支付" -> sb.append("\n\n【支付规则】仅「待付款」订单可支付；支付必须经过二次确认");
                case "发货" -> sb.append("\n\n【发货规则】仅「待发货」订单可发货；必须提供物流公司和快递单号");
                case "确认收货" -> sb.append("\n\n【确认收货规则】仅「已发货」订单可确认收货");
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
    // DTO → 注入上上下文格式化（替代 OrderTools 格式化器，避免业务模块依赖工具类）
    // ═══════════════════════════════════════════════════════════

    /** 订单 DTO → 注入上下文文本（参考 OrderTools.queryOrder 的格式化，仅含关键字段） */
    private String formatOrder(OrderDTO order) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📋 订单 %s\n", order.getOrderId()));
        if (order.getProductName() != null) {
            sb.append(String.format("商品：%s\n", order.getProductName()));
        }
        if (order.getAmount() != null) {
            sb.append(String.format("金额：¥%s\n", order.getAmount().toPlainString()));
        }
        if (order.getStatus() != null) {
            sb.append(String.format("状态：%s\n", order.getStatus()));
        }
        if (order.getProductType() != null && !order.getProductType().isEmpty()) {
            sb.append(String.format("商品类型：%s\n", order.getProductType()));
        }
        if (order.getContactName() != null && !order.getContactName().isEmpty()) {
            sb.append(String.format("收货人：%s\n", order.getContactName()));
        }
        if (order.getContactPhone() != null && !order.getContactPhone().isEmpty()) {
            sb.append(String.format("联系电话：%s\n", order.getContactPhone()));
        }
        if (order.getShippingAddress() != null && !order.getShippingAddress().isEmpty()) {
            sb.append(String.format("收货地址：%s\n", order.getShippingAddress()));
        }
        if (order.getPaymentMethod() != null && !order.getPaymentMethod().isEmpty()) {
            sb.append(String.format("支付方式：%s\n", order.getPaymentMethod()));
        }
        if (order.getCarrier() != null && !order.getCarrier().isEmpty()) {
            sb.append(String.format("物流公司：%s\n", order.getCarrier()));
            sb.append(String.format("运单号：%s\n", order.getTrackingNo()));
        }
        return sb.toString().trim();
    }

    /** 物流 DTO → 注入上下文文本（参考 OrderTools.trackLogistics 的格式化） */
    private String formatLogistics(LogisticsDTO logistics) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📦 快递单号 %s 物流信息\n",
                logistics.getTrackingNo() != null ? logistics.getTrackingNo() : ""));
        sb.append(String.format("所属订单：%s\n",
                logistics.getOrderId() != null ? logistics.getOrderId() : "未关联"));
        sb.append(String.format("物流公司：%s\n", logistics.getCompanyName()));
        sb.append(String.format("状态：%s\n", logistics.getStatus()));

        String detail = logistics.getLogisticsDetail();
        if (detail != null && !detail.isBlank() && !"[]".equals(detail)) {
            sb.append("\n最新轨迹：\n");
            for (String entry : parseTrajectory(detail)) {
                sb.append("  ").append(entry).append("\n");
            }
        } else {
            sb.append("\n暂无物流轨迹信息。\n");
        }
        return sb.toString().trim();
    }

    /** 从物流轨迹 JSON 提取「time location desc」条目（参考 OrderTools 的解析） */
    private java.util.List<String> parseTrajectory(String detail) {
        java.util.List<String> entries = new java.util.ArrayList<>();
        java.util.regex.Pattern objPattern = java.util.regex.Pattern.compile("\\{([^}]*)\\}");
        java.util.regex.Matcher objMatcher = objPattern.matcher(detail);
        while (objMatcher.find()) {
            String obj = objMatcher.group(1);
            String time = matchField(obj, "time");
            String location = matchField(obj, "location");
            String desc = matchField(obj, "desc");
            entries.add(String.format("%s  %s  %s", time, location, desc).trim());
        }
        return entries;
    }

    private String matchField(String obj, String field) {
        java.util.regex.Pattern p = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"");
        java.util.regex.Matcher m = p.matcher(obj);
        return m.find() ? m.group(1) : "";
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
     * 从消息中提取订单号（支持 ORD-xxx 与 BULK-xxx 格式）。
     */
    private String extractOrderId(String message) {
        if (message == null) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?i)\\b(?:ORD|BULK)-[A-Z0-9-]+\\b");
        java.util.regex.Matcher matcher = pattern.matcher(message);
        if (matcher.find()) {
            return matcher.group().toUpperCase(java.util.Locale.ROOT);
        }
        return null;
    }
}
