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

        // 退款政策/条件咨询不依赖具体订单。先用确定性规则识别，避免 LLM 将
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
            log.warn("[OrderIntent] 识别失败，默认 OTHER: {}", e.getMessage());
            return IntentType.OTHER;
        }
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
        REFUND("退款"),
        CANCEL("取消"),
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
