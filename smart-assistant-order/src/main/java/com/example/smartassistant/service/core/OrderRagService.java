/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.core;

import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.LogisticsDTO;
import com.example.smartassistant.common.tool.spi.dto.OrderDTO;
import com.example.smartassistant.service.core.OrderIntentService.IntentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

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
    private static final int RECENT_ORDER_LIMIT = 3;
    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OrderDataProvider orderData;

    public OrderRagService(OrderDataProvider orderData) {
        this.orderData = orderData;
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
        return retrieveWithQualityResultInternal(intent, message, null, false);
    }

    /**
     * 面向客服链路的安全检索：除订单号外必须同时匹配认证用户，防止 IDOR。
     */
    public RetrievalQualityResult retrieveWithQualityResult(IntentType intent, String message, Long authenticatedUserId) {
        return retrieveWithQualityResultInternal(intent, message, authenticatedUserId, true);
    }

    private RetrievalQualityResult retrieveWithQualityResultInternal(
            IntentType intent, String message, Long authenticatedUserId, boolean enforceOwnership) {
        if (intent == null || message == null) {
            return RetrievalQualityResult.noData("无效查询");
        }

        return switch (intent) {
            case QUERY_ORDER -> retrieveForQualityResult(message, "订单", authenticatedUserId, enforceOwnership);
            case REFUND -> retrieveForQualityResult(message, "退款", authenticatedUserId, enforceOwnership);
            case CREATE_ORDER -> RetrievalQualityResult.highQuality(
                    "【下单引导】请让用户提供以下信息：\n• 商品名称\n• 购买数量\n• 收货人姓名、电话、地址\n• 支付方式\n确认信息后调用 createOrder 创建订单。",
                    1.0);
            case CANCEL -> retrieveForQualityResult(message, "取消", authenticatedUserId, enforceOwnership);
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
    private RetrievalQualityResult retrieveForQualityResult(
            String message, String intentLabel, Long authenticatedUserId, boolean enforceOwnership) {
        String orderId = extractOrderId(message);
        if (orderId == null) {
            if (enforceOwnership && "订单".equals(intentLabel)) {
                return retrieveRecentOrders(authenticatedUserId);
            }
            return RetrievalQualityResult.insufficientEvidence(
                    "", 0.0,
                    "请提供订单号（格式：ORD-xxx）以便查询" + intentLabel + "信息。");
        }

        try {
            OrderDTO order = orderData.findOrderByOrderId(orderId);
            boolean foundOrder = order != null;

            if (!foundOrder) {
                return RetrievalQualityResult.insufficientEvidence(
                        "", 0.0,
                        "系统中未找到订单号「" + orderId + "」，请核对后重试。");
            }

            // 必须在读取物流或格式化订单详情之前完成鉴权，避免越权请求触碰下游数据。
            if (enforceOwnership) {
                if (authenticatedUserId == null || authenticatedUserId <= 0) {
                    return RetrievalQualityResult.insufficientEvidence(
                            "", 0.0, "请先登录后再查询订单信息。");
                }
                if (order.getUserId() == null || !authenticatedUserId.equals(order.getUserId())) {
                    // 不区分“订单不存在”和“无权访问”，避免通过错误信息枚举订单。
                    return RetrievalQualityResult.insufficientEvidence(
                            "", 0.0, "未找到该订单或您无权访问，请核对订单号后重试。");
                }
            }

            // 找到订单：根据不同意图附加额外信息
            StringBuilder sb = new StringBuilder();
            boolean hasLogistics = false;
            LogisticsDTO logistics = null;
            if ("订单".equals(intentLabel)) {
                try {
                    logistics = orderData.findLogisticsByOrderId(orderId);
                    if (logistics != null && logistics.getLogisticsDetail() != null
                            && !logistics.getLogisticsDetail().isBlank()
                            && !"[]".equals(logistics.getLogisticsDetail())) {
                        hasLogistics = true;
                    }
                } catch (Exception e) {
                    log.debug("[OrderRAG] 物流查询失败（可忽略）: {}", e.getMessage());
                }
            }

            boolean customerConversation = enforceOwnership
                    && "订单".equals(intentLabel)
                    && !asksStructuredFieldList(message);
            if (customerConversation) {
                sb.append(formatCustomerOrderResponse(order, message, logistics));
            } else {
                sb.append("【订单信息】").append(formatOrder(order));
                if ("订单".equals(intentLabel) && hasLogistics) {
                    sb.append("\n【物流信息】").append(
                            formatLogistics(logistics, extractTrajectoryLimit(message)));
                } else if ("订单".equals(intentLabel) && asksLogisticsProgress(message)) {
                    appendUnavailableLogisticsExplanation(sb, order);
                }
            }

            // 附加规则提示
            switch (intentLabel) {
                case "退款" -> sb.append("\n\n【退款规则】\n• 仅已发货/已签收的订单可申请退款\n• 退款需二次确认\n• 退款金额以实际支付金额为准");
                case "取消" -> sb.append("\n\n【取消规则】仅「待付款」和「待发货」状态的订单可取消");
                default -> {} // 查询无需额外规则
            }

            if ("订单".equals(intentLabel) && asksCancellationAssessment(message)) {
                appendCancellationAssessment(sb, order);
            }
            if ("订单".equals(intentLabel)) {
                appendVerificationMarker(sb, message);
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

    private RetrievalQualityResult retrieveRecentOrders(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            return RetrievalQualityResult.insufficientEvidence(
                    "", 0.0, "请先登录后再查询订单信息。");
        }

        try {
            List<OrderDTO> orders = orderData.findRecentOrdersByUserId(
                    authenticatedUserId, RECENT_ORDER_LIMIT);
            if (orders == null || orders.isEmpty()) {
                return RetrievalQualityResult.highQuality(
                        "当前账号暂未查到订单记录。若订单刚刚提交，请稍后刷新再试；您也可以继续咨询商品或售后问题。",
                        1.0);
            }

            StringBuilder answer = new StringBuilder("查到您最近的")
                    .append(orders.size()).append("笔订单：\n");
            for (int i = 0; i < orders.size(); i++) {
                OrderDTO order = orders.get(i);
                answer.append(i + 1).append(". ")
                        .append(safeText(order.getOrderId(), "订单号未知"))
                        .append("｜").append(safeText(order.getProductName(), "商品信息待补充"))
                        .append("｜").append(safeText(order.getStatus(), "状态未知"));
                if (order.getCreatedAt() != null) {
                    answer.append("｜").append(order.getCreatedAt().format(ORDER_DATE));
                }
                answer.append('\n');
            }
            answer.append("请选择下方要查看的订单，我会继续为您查询状态、物流或售后信息。");

            log.info("[OrderRAG] 最近订单查询成功: userId={}, count={}",
                    authenticatedUserId, orders.size());
            return RetrievalQualityResult.highQuality(answer.toString(), 1.0);
        } catch (Exception e) {
            log.warn("[OrderRAG] 最近订单查询失败: userId={}, error={}",
                    authenticatedUserId, e.getMessage());
            return RetrievalQualityResult.insufficientEvidence(
                    "", 0.3, "查询最近订单时出现系统错误，请稍后重试。");
        }
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean asksLogisticsProgress(String message) {
        return message != null && (message.contains("物流") || message.contains("快递")
                || message.contains("运单") || message.contains("配送"));
    }

    private boolean asksStructuredFieldList(String message) {
        return message != null && (message.contains("列出") || message.contains("逐项")
                || message.contains("完整字段") || message.contains("核验标记")
                || extractTrajectoryLimit(message) > 0);
    }

    private boolean asksRecipientInfo(String message) {
        return message != null && (message.contains("收货地址") || message.contains("收货信息")
                || message.contains("收货人") || message.contains("联系电话")
                || message.contains("联系方式"));
    }

    private boolean asksPaymentInfo(String message) {
        return message != null && (message.contains("支付方式") || message.contains("付款方式")
                || message.contains("怎么付款") || message.contains("如何付款"));
    }

    /** 面向登录用户的精简客服话术：先回答结论，仅展示与问题有关的字段。 */
    private String formatCustomerOrderResponse(OrderDTO order, String message, LogisticsDTO logistics) {
        String orderId = safeText(order.getOrderId(), "当前订单");
        String product = safeText(order.getProductName(), "这件商品");
        String status = safeText(order.getStatus(), "状态待更新");
        StringBuilder answer = new StringBuilder();

        answer.append("我帮您看了一下，您选择的是“").append(product)
                .append("”（订单号 ").append(orderId).append("）。\n\n");

        if (asksLogisticsProgress(message)) {
            if (status.contains("待付款") || status.contains("未付款")) {
                answer.append("这笔订单当前状态是「").append(status)
                        .append("」，还没有进入发货流程，所以暂时不会有物流信息。")
                        .append("完成付款后，商家才会安排发货并生成运单。");
                if (order.getAmount() != null) {
                    answer.append("\n\n订单金额为 ¥").append(order.getAmount().toPlainString())
                            .append("。如果您暂时不需要，也可以先了解取消订单的方式。");
                }
            } else if (status.contains("待发货") || status.contains("备货")) {
                answer.append("这笔订单当前状态是「").append(status)
                        .append("」。商家正在处理订单，但还没有交给快递公司，")
                        .append("因此暂时没有运单和物流轨迹。您可以稍后再查，或让我帮您了解预计发货时间。");
            } else if (logistics != null) {
                String company = safeText(logistics.getCompanyName(), safeText(order.getCarrier(), "承运方"));
                String logisticsStatus = customerLogisticsStatus(logistics.getStatus(), status);
                answer.append("这笔订单已经发货，目前由").append(company)
                        .append("配送，物流状态是「").append(logisticsStatus).append("」。");
                String latest = latestTrajectory(logistics.getLogisticsDetail());
                if (latest != null) {
                    answer.append("\n\n最近一条物流更新：").append(latest).append("。");
                }
                answer.append("\n\n如果物流长时间没有更新，我可以继续帮您判断是否需要催件。");
            } else {
                answer.append("这笔订单当前状态是「").append(status)
                        .append("」，但暂时还没有查到物流轨迹。")
                        .append("如果订单刚刚发货，物流信息可能会稍晚同步，建议过一会儿再查看。");
            }
        } else {
            answer.append("这笔订单当前状态是「").append(status).append("」");
            if (order.getAmount() != null) {
                answer.append("，订单金额为 ¥").append(order.getAmount().toPlainString());
            }
            answer.append("。");

            if (asksPaymentInfo(message) && order.getPaymentMethod() != null
                    && !order.getPaymentMethod().isBlank()) {
                answer.append("\n\n使用的支付方式是").append(order.getPaymentMethod()).append("。");
            }
        }

        if (asksRecipientInfo(message)) {
            answer.append("\n\n为保护您的隐私，收货信息已做脱敏处理：");
            if (order.getContactName() != null && !order.getContactName().isBlank()) {
                answer.append("\n收货人：").append(maskName(order.getContactName()));
            }
            if (order.getContactPhone() != null && !order.getContactPhone().isBlank()) {
                answer.append("\n联系电话：").append(maskPhone(order.getContactPhone()));
            }
            if (order.getShippingAddress() != null && !order.getShippingAddress().isBlank()) {
                answer.append("\n收货地址：").append(maskAddress(order.getShippingAddress()));
            }
        }

        return answer.toString().trim();
    }

    private String customerLogisticsStatus(String logisticsStatus, String orderStatus) {
        if (logisticsStatus == null || logisticsStatus.isBlank()) return orderStatus;
        return switch (logisticsStatus.toLowerCase(java.util.Locale.ROOT)) {
            case "in_transit" -> "运输中";
            case "delivered" -> "已签收";
            case "pending", "created" -> "等待揽收";
            case "shipped", "picked_up" -> "已揽收";
            default -> logisticsStatus;
        };
    }

    private String latestTrajectory(String detail) {
        if (detail == null || detail.isBlank() || "[]".equals(detail)) return null;
        java.util.List<String> trajectories = parseTrajectory(detail);
        if (trajectories.isEmpty()) return null;
        trajectories.sort(java.util.Comparator.reverseOrder());
        return trajectories.get(0);
    }

    private void appendUnavailableLogisticsExplanation(StringBuilder answer, OrderDTO order) {
        String status = safeText(order.getStatus(), "当前状态");
        answer.append("\n【物流信息】");
        if (status.contains("待付款") || status.contains("未付款")) {
            answer.append("当前订单状态为“").append(status)
                    .append("”，尚未进入发货流程，暂未生成物流信息。请完成付款后再查询物流进度。");
        } else if (status.contains("待发货") || status.contains("备货")) {
            answer.append("当前订单状态为“").append(status)
                    .append("”，商家尚未交给承运方，暂未生成运单或物流轨迹。");
        } else {
            answer.append("当前暂未查询到物流轨迹。订单状态为“").append(status)
                    .append("”，如刚发货请稍后再试。");
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
            sb.append(String.format("联系电话：%s\n", maskPhone(order.getContactPhone())));
        }
        if (order.getShippingAddress() != null && !order.getShippingAddress().isEmpty()) {
            sb.append(String.format("收货地址：%s\n", maskAddress(order.getShippingAddress())));
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

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "****";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String maskName(String name) {
        if (name == null || name.isBlank()) return "*";
        return name.substring(0, 1) + "**";
    }

    private String maskAddress(String address) {
        if (address == null || address.isBlank()) return "****";
        int visible = Math.min(6, address.length());
        return address.substring(0, visible) + "****";
    }

    /** 物流 DTO → 注入上下文文本（参考 OrderTools.trackLogistics 的格式化） */
    private String formatLogistics(LogisticsDTO logistics, int trajectoryLimit) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📦 快递单号 %s 物流信息\n",
                logistics.getTrackingNo() != null ? logistics.getTrackingNo() : ""));
        sb.append(String.format("所属订单：%s\n",
                logistics.getOrderId() != null ? logistics.getOrderId() : "未关联"));
        sb.append(String.format("物流公司：%s\n", logistics.getCompanyName()));
        sb.append(String.format("状态：%s\n", logistics.getStatus()));

        String detail = logistics.getLogisticsDetail();
        if (detail != null && !detail.isBlank() && !"[]".equals(detail)) {
            java.util.List<String> trajectories = parseTrajectory(detail);
            trajectories.sort(java.util.Comparator.reverseOrder());
            if (trajectoryLimit > 0 && trajectories.size() > trajectoryLimit) {
                trajectories = trajectories.subList(0, trajectoryLimit);
            }
            sb.append(trajectoryLimit > 0
                    ? "\n最新" + trajectoryLimit + "条轨迹：\n"
                    : "\n最新轨迹：\n");
            for (String entry : trajectories) {
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

    private boolean asksCancellationAssessment(String message) {
        if (message == null || !message.contains("取消")) {
            return false;
        }
        return message.contains("条件") || message.contains("能否")
                || message.contains("是否") || message.contains("可以")
                || message.contains("满足") || message.contains("判断");
    }

    private void appendCancellationAssessment(StringBuilder sb, OrderDTO order) {
        String status = order.getStatus() != null ? order.getStatus() : "未知";
        boolean cancellable = "待付款".equals(status) || "待发货".equals(status);
        sb.append("\n\n【取消条件判断】\n")
                .append("规则：仅「待付款」和「待发货」状态的订单可直接取消。\n")
                .append("当前状态：").append(status).append("，")
                .append(cancellable ? "满足直接取消条件。" : "不满足直接取消条件。");
        if (!cancellable && ("已发货".equals(status) || "已签收".equals(status))) {
            sb.append("如不再需要，请按拒收或退货退款流程处理。");
        }
    }

    private void appendVerificationMarker(StringBuilder sb, String message) {
        String marker = extractVerificationMarker(message);
        if (marker != null) {
            sb.append("\n\n【核验标记】").append(marker);
        }
    }

    private String extractVerificationMarker(String message) {
        if (message == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("核验标记\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9_-]{2,64})")
                .matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private int extractTrajectoryLimit(String message) {
        if (message == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("最新\\s*(\\d+|[一二两三四五])\\s*条")
                .matcher(message);
        if (!matcher.find()) return 0;
        return switch (matcher.group(1)) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            default -> {
                try {
                    yield Math.max(1, Math.min(20, Integer.parseInt(matcher.group(1))));
                } catch (NumberFormatException ignored) {
                    yield 0;
                }
            }
        };
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
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?i)ORD-[A-Z0-9][A-Z0-9_-]{2,63}");
        java.util.regex.Matcher matcher = pattern.matcher(message);
        if (matcher.find()) {
            return matcher.group().toUpperCase(java.util.Locale.ROOT);
        }
        return null;
    }
}
