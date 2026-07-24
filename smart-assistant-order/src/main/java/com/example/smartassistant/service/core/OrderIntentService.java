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

import java.util.Locale;

/**
 * ⭐ 订单意图识别服务。
 * <p>
 * 使用 LLM 快速识别用户意图，将用户消息分类为：
 * <ul>
 *   <li>{@link IntentType#CREATE_ORDER} — 下单</li>
 *   <li>{@link IntentType#QUERY_ORDER} — 查询订单</li>
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

        IntentType keywordIntent = detectCommonIntent(message);
        if (keywordIntent != null) {
            log.info("[OrderIntent] 关键词识别结果: message={}, intent={}", message, keywordIntent);
            return keywordIntent;
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
     * Common order operations should not depend on an LLM classification round trip.
     * The LLM remains the fallback for ambiguous natural-language requests.
     */
    IntentType detectCommonIntent(String message) {
        String normalized = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");

        if (containsAny(normalized, "退款", "退货", "售后")) {
            return IntentType.REFUND;
        }
        if (containsAny(normalized, "取消订单", "取消下单", "撤销订单")) {
            return IntentType.CANCEL;
        }
        if (containsAny(normalized, "下单", "提交订单", "立即购买")) {
            return IntentType.CREATE_ORDER;
        }
        if (containsAny(normalized,
                "物流", "快递", "配送", "运单", "发货", "到哪",
                "订单状态", "查询订单", "查订单", "最近一笔订单")) {
            return IntentType.QUERY_ORDER;
        }
        return null;
    }

    private boolean containsAny(String message, String... keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 结构化输出载体 — 用于 {@link AiChatService#entity} 绑定，避免直接解析文本标签。
     *
     * @param intent 识别出的意图
     */
    public record IntentResult(IntentType intent) {
    }


    /**
     * 订单意图枚举。
     */
    public enum IntentType {
        CREATE_ORDER("下单"),
        QUERY_ORDER("查询订单"),
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
