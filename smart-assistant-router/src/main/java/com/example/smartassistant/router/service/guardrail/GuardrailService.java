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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    // ═══════════════════════════════════════════════════════════
    // 情绪分级干预（对标 RAG 生产化文章②：先共情安抚，再分级处置，安全兜底优先）
    // 采用轻量化确定性关键词检测，无需 LLM 调用，低延迟、可审计。
    // ═══════════════════════════════════════════════════════════

    /** 自伤/伤人倾向（最高风险 → HEAVY） */
    private static final List<String> SELF_HARM_KEYWORDS = List.of(
            "不想活", "活不下去", "想自杀", "自杀", "自残", "轻生",
            "结束这一切", "活着没意思", "不如死了", "不想活了", "结束生命"
    );
    /** 抑郁/崩溃（→ MEDIUM） */
    private static final List<String> DEPRESSION_KEYWORDS = List.of(
            "抑郁", "绝望", "崩溃", "想哭", "提不起劲", "活着没意义",
            "空虚", "孤独", "撑不下去", "没盼头"
    );
    /** 愤怒（→ MEDIUM） */
    private static final List<String> ANGER_KEYWORDS = List.of(
            "气死", "愤怒", "恼火", "受不了", "烦死了", "暴躁", "火大", "气愤"
    );
    /** 焦虑/不安（→ LIGHT） */
    private static final List<String> ANXIETY_KEYWORDS = List.of(
            "焦虑", "担心", "紧张", "害怕", "不安", "恐慌", "着急", "忐忑"
    );

    /**
     * 检测用户文本中的情绪风险等级与类别。
     *
     * <p>分级规则（取最高命中等级）：</p>
     * <ul>
     *     <li>命中自伤关键词 → {@link EmotionLevel#HEAVY}（禁用工具 + 需人工介入）</li>
     *     <li>命中抑郁/愤怒关键词 → {@link EmotionLevel#MEDIUM}（暂停任务先疏导）</li>
     *     <li>仅命中焦虑关键词 → {@link EmotionLevel#LIGHT}（共情承接）</li>
     * </ul>
     *
     * <p>与 {@link #check(String)} 正交：护栏关注「高风险业务操作」，
     * 情绪检测关注「用户心理安全」，二者可同时触发。</p>
     *
     * @param text 用户原始输入
     * @return 情绪检测结果（未命中返回 {@link EmotionCheckResult#none()}）
     */
    public EmotionCheckResult checkEmotion(String text) {
        if (!properties.isEnabled() || text == null || text.isBlank()) {
            return EmotionCheckResult.none();
        }

        Set<EmotionCategory> categories = new LinkedHashSet<>();
        List<String> signals = new ArrayList<>();

        for (String kw : SELF_HARM_KEYWORDS) {
            if (text.contains(kw)) { categories.add(EmotionCategory.SELF_HARM); signals.add(kw); }
        }
        for (String kw : DEPRESSION_KEYWORDS) {
            if (text.contains(kw)) { categories.add(EmotionCategory.DEPRESSION); signals.add(kw); }
        }
        for (String kw : ANGER_KEYWORDS) {
            if (text.contains(kw)) { categories.add(EmotionCategory.ANGER); signals.add(kw); }
        }
        for (String kw : ANXIETY_KEYWORDS) {
            if (text.contains(kw)) { categories.add(EmotionCategory.ANXIETY); signals.add(kw); }
        }

        if (categories.isEmpty()) {
            return EmotionCheckResult.none();
        }

        EmotionLevel level = computeLevel(categories);
        boolean disableTools = level == EmotionLevel.HEAVY;
        boolean requiresHumanHandoff = level == EmotionLevel.HEAVY;
        String guidance = buildGuidance(level, categories);

        log.warn("[Guardrail] 💗 情绪风险检测: level={}, categories={}, signals={}, disableTools={}",
                level, categories, signals, disableTools);

        return new EmotionCheckResult(level, categories, signals, disableTools, requiresHumanHandoff, guidance);
    }

    private static EmotionLevel computeLevel(Set<EmotionCategory> categories) {
        if (categories.contains(EmotionCategory.SELF_HARM)) return EmotionLevel.HEAVY;
        if (categories.contains(EmotionCategory.DEPRESSION) || categories.contains(EmotionCategory.ANGER)) {
            return EmotionLevel.MEDIUM;
        }
        return EmotionLevel.LIGHT;
    }

    private static String buildGuidance(EmotionLevel level, Set<EmotionCategory> categories) {
        return switch (level) {
            case HEAVY -> "检测到极高的情绪风险，已暂停常规工具调用。请优先寻求专业帮助："
                    + "全国24小时心理危机干预热线 400-161-9995（北京 010-82951332）。"
                    + "我在这里陪你，但紧急情况请联系专业人士或拨打 120。";
            case MEDIUM -> "我感受到你此刻情绪比较强烈，我们先不急着处理任务，慢慢来。"
                    + "你愿意多和我说一点吗？";
            case LIGHT -> "听起来你有些" + describe(categories) + "，我理解你的感受，我们一起梳理看看。";
            default -> null;
        };
    }

    private static String describe(Set<EmotionCategory> categories) {
        List<String> labels = new ArrayList<>();
        if (categories.contains(EmotionCategory.ANXIETY)) labels.add("担心或不安");
        if (categories.contains(EmotionCategory.ANGER)) labels.add("气愤");
        if (categories.contains(EmotionCategory.DEPRESSION)) labels.add("低落");
        return String.join("、", labels);
    }

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
