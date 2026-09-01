/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.common.rag.advisor.AiChatService;

import java.util.List;
import java.util.regex.Pattern;

/**
 * ⭐ 订单意图识别服务。
 * <p>
 * 使用 LLM 快速识别用户意图，将用户消息分类为：
 * <ul>
 *   <li>{@link IntentType#CREATE_ORDER} — 下单</li>
 *   <li>{@link IntentType#QUERY_ORDER} — 查询订单</li>
 *   <li>{@link IntentType#REFUND_POLICY} — 退款/退货政策咨询</li>
 *   <li>{@link IntentType#REFUND} — 退款</li>
 *   <li>{@link IntentType#CANCEL} — 取消</li>
 *   <li>{@link IntentType#PAY} — 支付</li>
 *   <li>{@link IntentType#SHIP} — 发货</li>
 *   <li>{@link IntentType#TRACK_LOGISTICS} — 查询物流</li>
 *   <li>{@link IntentType#CONFIRM_DELIVERY} — 确认收货</li>
 *   <li>{@link IntentType#OTHER} — 其他</li>
 * </ul>
 * </p>
 *
 * <p>意图识别结果直接影响后续的 RAG 预检索策略。</p>
 *
 * <p>结构化输出复用 {@link AiChatService#entity(ChatModel, String, String, Class)}，
 * 直接把 LLM 响应绑定为 {@link IntentResult}，避免文本标签的脆弱匹配，
 * 同时继承统一 Advisor 链（安全护栏 / Token 审计等）。</p>
 */
@Service
public class OrderIntentService {

    private static final Logger log = LoggerFactory.getLogger(OrderIntentService.class);
    private static final Pattern ORDER_ID = Pattern.compile("(?i)\\bORD-[A-Z0-9-]+\\b");

    private final AiChatService aiChatService;
    private final ChatModel lightModel;
    private final PromptManager promptManager;

    public OrderIntentService(AiChatService aiChatService,
                              @Qualifier("lightChatModel") ChatModel lightModel,
                              PromptManager promptManager) {
        this.aiChatService = aiChatService;
        this.lightModel = lightModel;
        this.promptManager = promptManager;
    }

    /**
     * 识别用户消息的意图。
     *
     * @param message 用户消息
     * @return 意图类型，无法识别时返回 {@link IntentType#OTHER}
     */
    public IntentType detect(String message) {
        if (message == null || message.isBlank()) {
            return IntentType.OTHER;
        }

        // 清晰的“我的订单/订单列表”属于已认证用户的只读数据查询。
        // 该场景无需依赖模型，且必须和“准备创建订单/立即下单”等写操作严格分离。
        if (isReadOnlyOrderListQuery(message)) {
            log.info("[OrderIntent] 确定性识别只读订单列表查询: message={}", message);
            return IntentType.QUERY_ORDER;
        }

        if (isOrderPreparationGuidance(message)) {
            log.info("[OrderIntent] 确定性识别下单前资料说明: message={}", message);
            return IntentType.ORDER_PREPARATION_GUIDANCE;
        }

        // "如何查询、取消和申请售后"描述的是只读操作指南，不是对某一订单
        // 立即执行多个互斥动作。领域层先固定这个安全边界，避免单标签模型把
        // 复合说明误判为 OTHER，或误触发需要订单号/人工确认的写操作。
        if (isOrderLifecycleGuidance(message)) {
            log.info("[OrderIntent] 确定性识别订单生命周期说明: message={}", message);
            return IntentType.ORDER_GUIDANCE;
        }

        // 退款政策/条件咨询不依赖具体订单。用确定性规则识别，避免 LLM 将
        // “商品退货退款需要满足哪些条件”误判成需要订单号的退款操作。
        if (isRefundPolicyQuestion(message)) {
            log.info("[OrderIntent] 确定性识别退款政策咨询: message={}", message);
            return IntentType.REFUND_POLICY;
        }

        // P2 Prompt 外部化：prompts/order/intent-classifier.txt
        String system = promptManager.orderIntentClassifier();

        try {
            IntentResult result = aiChatService.entity(
                    lightModel, system, "用户消息：" + message, IntentResult.class);
            IntentType intent = result != null && result.intent() != null ? result.intent() : IntentType.OTHER;
            log.info("[OrderIntent] 识别结果: message={}, intent={}", message, intent);
            return intent;
        } catch (Exception e) {
            IntentType fallback = detectSafeReadOnlyFallback(message);
            if (fallback != IntentType.OTHER) {
                log.warn("[OrderIntent] 模型识别失败，降级到只读意图 {}: {}",
                        fallback, e.getMessage());
                return fallback;
            }
            log.warn("[OrderIntent] 识别失败，保持 OTHER（禁止降级触发写操作）: {}", e.getMessage());
            return IntentType.OTHER;
        }
    }

    /**
     * 模型不可用时只允许降级识别可验证的读操作。创建、支付、取消、退款、
     * 发货和确认收货一律保持 OTHER，避免分类故障转化为业务写入。
     */
    static IntentType detectSafeReadOnlyFallback(String message) {
        if (isReadOnlyOrderListQuery(message)) return IntentType.QUERY_ORDER;
        if (message == null || message.isBlank() || hasWriteOperation(message)) {
            return IntentType.OTHER;
        }
        if (ORDER_ID.matcher(message).find()) {
            if (containsAny(message, List.of("查物流", "查询物流", "查看物流", "物流信息", "物流轨迹"))) {
                return IntentType.TRACK_LOGISTICS;
            }
            if (containsAny(message, List.of("查询", "查看", "看看", "状态", "详情", "订单信息"))) {
                return IntentType.QUERY_ORDER;
            }
        }
        return IntentType.OTHER;
    }

    static boolean isReadOnlyOrderListQuery(String message) {
        if (message == null || message.isBlank() || hasWriteOperation(message)) return false;
        String normalized = message.replaceAll("[\\s，,。.!！?？]", "");
        boolean readVerb = containsAny(normalized, List.of(
                "查看", "查询", "列出", "显示", "看看", "有哪些", "有没有"));
        boolean ownedOrderScope = containsAny(normalized, List.of(
                "我的订单", "本人订单", "订单列表", "订单记录", "历史订单", "已有订单"))
                || (normalized.contains("我") && normalized.contains("订单"));
        return ownedOrderScope && (readVerb
                || normalized.equals("我的订单") || normalized.equals("订单列表"));
    }

    private static boolean hasWriteOperation(String message) {
        return containsAny(message, List.of(
                "帮我下单", "替我下单", "我要下单", "立即下单", "现在下单", "直接下单", "创建订单",
                "确认支付", "立即支付", "支付订单", "帮我支付",
                "帮我取消", "替我取消", "我要取消", "立即取消", "取消订单",
                "帮我退款", "我要退款", "办理退款", "发起退款", "申请退款",
                "帮我退货", "我要退货", "办理退货", "发起退货", "申请退货",
                "帮我发货", "立即发货", "执行发货", "确认收货"));
    }

    /**
     * 结构化输出载体 — 用于 {@link AiChatService#entity} 绑定，避免直接解析文本标签。
     *
     * @param intent 识别出的意图
     */
    public record IntentResult(IntentType intent) {
    }

    static boolean isRefundPolicyQuestion(String message) {
        if (message == null || message.isBlank()) return false;

        boolean refundTopic = containsAny(message, List.of("退款", "退货", "退钱"));
        if (!refundTopic) return false;

        // 明确针对本人订单发起操作或查询处理进度，仍需走 REFUND 并收集订单号。
        boolean actionOrStatus = containsAny(message, List.of(
                "我要退款", "我想退款", "帮我退款", "给我退款", "办理退款", "发起退款",
                "我要退货", "我想退货", "帮我退货", "给我退货", "办理退货", "发起退货",
                "我的退款", "退款进度", "退款状态", "退款到哪", "退货进度", "退货状态"));
        if (actionOrStatus) return false;

        return containsAny(message, List.of(
                "条件", "政策", "规则", "流程", "要求", "材料", "资格", "时效", "期限",
                "多久到账", "多久", "多长时间", "怎么", "如何", "能不能", "是否可以",
                "需要满足", "需要哪些", "需要什么", "几天"));
    }

    static boolean isOrderLifecycleGuidance(String message) {
        if (message == null || message.isBlank() || ORDER_ID.matcher(message).find()) return false;

        boolean asksForExplanation = containsAny(message, List.of(
                "如何", "怎么", "怎样", "说明", "介绍", "流程", "规则", "条件",
                "需要什么", "需要哪些", "以后", "后续", "操作指南"));
        if (!asksForExplanation) return false;

        boolean requestsImmediateAction = containsAny(message, List.of(
                "帮我取消", "给我取消", "替我取消", "我要取消", "立即取消", "现在取消",
                "帮我退款", "给我退款", "替我退款", "我要退款", "立即退款", "发起退款",
                "帮我退货", "替我退货", "我要退货", "发起退货"));
        if (requestsImmediateAction) return false;

        int topics = 0;
        if (containsAny(message, List.of("查询订单", "订单查询", "订单状态", "物流查询"))) topics++;
        if (containsAny(message, List.of("取消订单", "订单取消"))) topics++;
        if (containsAny(message, List.of("售后", "退款", "退货"))) topics++;
        boolean onlyRefundTopic = topics == 1
                && containsAny(message, List.of("退款", "退货", "退钱"));
        return topics >= 2 || (topics == 1 && !onlyRefundTopic);
    }

    static boolean isOrderPreparationGuidance(String message) {
        if (message == null || message.isBlank() || ORDER_ID.matcher(message).find()) return false;
        boolean beforeCreation = containsAny(message, List.of(
                "下单前", "创建订单前", "订单创建前", "准备下单", "最终选定商品"));
        boolean asksForChecklist = containsAny(message, List.of(
                "哪些信息", "什么信息", "确认哪些", "需要确认", "需要准备", "需要提供",
                "需确认", "确认的信息", "确认信息", "资料", "清单"));
        boolean requestsImmediateCreation = containsAny(message, List.of(
                "帮我下单", "替我下单", "立即下单", "现在下单", "直接下单"));
        return beforeCreation && asksForChecklist && !requestsImmediateCreation;
    }

    private static boolean containsAny(String value, List<String> markers) {
        return markers.stream().anyMatch(value::contains);
    }


    /**
     * 订单意图枚举。
     */
    public enum IntentType {
        CREATE_ORDER("下单"),
        QUERY_ORDER("查询订单"),
        REFUND_POLICY("退款政策"),
        ORDER_PREPARATION_GUIDANCE("下单前资料说明"),
        ORDER_GUIDANCE("订单操作说明"),
        REFUND("退款"),
        CANCEL("取消"),
        PAY("支付"),
        SHIP("发货"),
        TRACK_LOGISTICS("查询物流"),
        CONFIRM_DELIVERY("确认收货"),
        OTHER("其他");

        private final String label;

        IntentType(String label) {
            this.label = label;
        }

        public String getLabel() { return label; }

        /**
         * 从 LLM 返回的标签解析意图。
         */
        public static IntentType fromLabel(String label) {
            if (label == null || label.isBlank()) return OTHER;
            for (IntentType type : values()) {
                if (type.label.equals(label.trim())) {
                    return type;
                }
            }
            return OTHER;
        }
    }
}
