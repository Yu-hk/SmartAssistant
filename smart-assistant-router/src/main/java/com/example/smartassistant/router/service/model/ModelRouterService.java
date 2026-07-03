/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ⭐ 多模型路由服务 — 根据问题复杂度分配不同模型。
 * <p>
 * 策略：
 * <ul>
 *   <li><b>简单问题</b>（短文本、问候、明确意图）→ 轻量模型（Ollama qwen2.5:3b，低成本）</li>
 *   <li><b>中等问题</b>（查询、普通问答）→ 默认模型</li>
 *   <li><b>复杂问题</b>（多跳推理、长文本、跨领域）→ 强模型（DeepSeek V4-Flash，高成本）</li>
 * </ul>
 * </p>
 */
@Service
public class ModelRouterService {

    private static final Logger log = LoggerFactory.getLogger(ModelRouterService.class);

    /** 轻量模型（简单问题） */
    private final ChatClient lightClient;

    /** 默认模型（中等问题） */
    private final ChatClient defaultClient;

    /** 强模型（复杂问题） */
    private final ChatClient heavyClient;

    @Value("${router.model-router.light-threshold:20}")
    private int lightThreshold;

    @Value("${router.model-router.heavy-threshold:100}")
    private int heavyThreshold;

    /** 始终走轻量模型的意图关键词 */
    private static final List<String> LIGHT_INTENT_KEYWORDS = List.of(
            "你好", "在吗", "谢谢", "再见", "hi", "hello", "早上好", "晚上好"
    );

    /** 始终走强模型的关键词 */
    private static final List<String> HEAVY_INTENT_KEYWORDS = List.of(
            "对比", "分析", "总结", "详细说明", "为什么", "如何实现",
            "区别", "优缺点", "方案", "设计", "架构"
    );

    public ModelRouterService(
            @Qualifier("lightChatModel") ChatClient lightClient,
            @Qualifier("defaultChatModel") ChatClient defaultClient,
            @Qualifier("heavyChatModel") ChatClient heavyClient) {
        this.lightClient = lightClient;
        this.defaultClient = defaultClient;
        this.heavyClient = heavyClient;
    }

    /**
     * 根据问题选择模型。
     *
     * @param question 用户问题
     * @return 对应的 ChatClient
     */
    public ChatClient selectModel(String question) {
        ModelTier tier = classifyTier(question);
        return switch (tier) {
            case LIGHT -> {
                log.debug("[ModelRouter] 轻量模型: question='{}'", truncate(question));
                yield lightClient;
            }
            case HEAVY -> {
                log.debug("[ModelRouter] 强模型: question='{}'", truncate(question));
                yield heavyClient;
            }
            default -> {
                log.debug("[ModelRouter] 默认模型: question='{}'", truncate(question));
                yield defaultClient;
            }
        };
    }

    /**
     * 获取模型等级名称（用于日志/追踪）。
     */
    public String getModelTierName(String question) {
        return classifyTier(question).name();
    }

    /**
     * 分类问题复杂度。
     */
    private ModelTier classifyTier(String question) {
        if (question == null || question.isBlank()) return ModelTier.DEFAULT;

        String q = question.trim();

        // 关键词快速判断
        for (String kw : LIGHT_INTENT_KEYWORDS) {
            if (q.startsWith(kw) || q.equals(kw)) return ModelTier.LIGHT;
        }
        for (String kw : HEAVY_INTENT_KEYWORDS) {
            if (q.contains(kw)) return ModelTier.HEAVY;
        }

        // 基于长度判断
        int len = q.length();
        if (len > heavyThreshold) return ModelTier.HEAVY;
        if (len < lightThreshold) return ModelTier.LIGHT;

        return ModelTier.DEFAULT;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }

    /** 模型等级 */
    public enum ModelTier {
        /** 轻量模型（Ollama 本地，低延迟低成本） */
        LIGHT,
        /** 默认模型 */
        DEFAULT,
        /** 强模型（DeepSeek V4-Flash，高能力高成本） */
        HEAVY
    }
}
