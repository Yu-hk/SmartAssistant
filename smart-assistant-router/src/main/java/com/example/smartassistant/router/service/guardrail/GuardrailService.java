/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.guardrail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 确定性护栏服务。
 *
 * <p>职责：当用户输入命中高风险关键词或意图时，强制触发 RAG 增强，
 * 避免 LLM 误判导致对退款/投诉/取消等高敏感操作给出不准确的回复。</p>
 *
 * <p>典型应用场景：</p>
 * <ul>
 *     <li>用户输入"我要退款"→ 命中 enforced-terms → 强制 RAG + 跳过短路</li>
 *     <li>意图识别为 REFUND → 命中 enforced-intents → 强制 RAG</li>
 * </ul>
 */
@Service
public class GuardrailService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailService.class);

    private final GuardrailProperties properties;

    public GuardrailService(GuardrailProperties properties) {
        this.properties = properties;
    }

    /**
     * 检查用户问题是否命中任何护栏规则。
     *
     * @param question 用户原始问题
     * @return 命中结果（空表示未命中）
     */
    public GuardrailCheckResult check(String question) {
        if (!properties.isEnabled()) {
            return GuardrailCheckResult.notTriggered();
        }
        if (question == null || question.isBlank()) {
            return GuardrailCheckResult.notTriggered();
        }

        List<String> matchedTerms = new ArrayList<>();
        for (String term : properties.getEnforcedTerms()) {
            if (question.contains(term)) {
                matchedTerms.add(term);
            }
        }

        if (matchedTerms.isEmpty()) {
            return GuardrailCheckResult.notTriggered();
        }

        log.warn("[Guardrail] ⚠️ 护栏触发: question='{}', matchedTerms={}, skipShortCircuit={}, forceRag={}",
                truncate(question),
                matchedTerms,
                properties.isSkipShortCircuit(),
                properties.isForceRag());

        return new GuardrailCheckResult(
                true,
                matchedTerms,
                properties.getEnforcedIntents(),
                properties.isSkipShortCircuit(),
                properties.isForceRag()
        );
    }

    /**
     * 根据意图标签检查是否命中护栏规则。
     *
     * @param intentTag 意图标签
     * @return 命中结果
     */
    public GuardrailCheckResult checkByIntent(String intentTag) {
        if (!properties.isEnabled() || intentTag == null || intentTag.isBlank()) {
            return GuardrailCheckResult.notTriggered();
        }

        boolean hitIntent = properties.getEnforcedIntents().stream()
                .anyMatch(enforced -> intentTag.toUpperCase().contains(enforced));

        if (!hitIntent) {
            return GuardrailCheckResult.notTriggered();
        }

        log.warn("[Guardrail] ⚠️ 意图驱动护栏触发: intent={}, skipShortCircuit={}, forceRag={}",
                intentTag, properties.isSkipShortCircuit(), properties.isForceRag());

        return new GuardrailCheckResult(
                true,
                Collections.emptyList(),
                properties.getEnforcedIntents(),
                properties.isSkipShortCircuit(),
                properties.isForceRag()
        );
    }

    public GuardrailProperties getProperties() { return properties; }

    private static String truncate(String str) {
        return str != null && str.length() > 60 ? str.substring(0, 60) + "..." : str;
    }

    // ═══════════════════════════════════════════════════════════
    // 护栏检查结果
    // ═══════════════════════════════════════════════════════════

    /**
     * 护栏检查结果。
     */
    public record GuardrailCheckResult(
            /** 是否触发护栏 */
            boolean triggered,
            /** 命中的关键术语 */
            List<String> matchedTerms,
            /** 强制作用的意图列表 */
            List<String> enforcedIntents,
            /** 是否跳过短路（经验匹配 + 关键词快车道） */
            boolean skipShortCircuit,
            /** 是否强制 RAG 增强 */
            boolean forceRag
    ) {
        private static final GuardrailCheckResult NOT_TRIGGERED =
                new GuardrailCheckResult(false, List.of(), List.of(), false, false);

        public static GuardrailCheckResult notTriggered() { return NOT_TRIGGERED; }
    }
}
