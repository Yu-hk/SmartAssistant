/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.guardrail;

import java.util.List;
import java.util.Set;

/**
 * 情绪检测结果。
 *
 * <p>由 {@link GuardrailService#checkEmotion(String)} 产出，供路由层做分级干预：</p>
 * <ul>
 *     <li>{@link #level()} 决定处置强度（见 {@link EmotionLevel}）</li>
 *     <li>{@link #disableTools()} 为 true 时（HEAVY）路由层应禁止任何工具调用</li>
 *     <li>{@link #requiresHumanHandoff()} 为 true 时（HEAVY）应触发人工/专业介入</li>
 *     <li>{@link #guidance()} 为可直接注入回复的安抚/求助话术</li>
 * </ul>
 */
public record EmotionCheckResult(
        /** 情绪等级 */
        EmotionLevel level,
        /** 命中的情绪类别集合 */
        Set<EmotionCategory> categories,
        /** 命中的关键词信号（用于审计/日志） */
        List<String> signals,
        /** 是否需禁用工具调用 */
        boolean disableTools,
        /** 是否需人工/专业介入 */
        boolean requiresHumanHandoff,
        /** 安抚/求助引导话术（可为 null） */
        String guidance
) {
    private static final EmotionCheckResult NONE =
            new EmotionCheckResult(EmotionLevel.NONE, Set.of(), List.of(), false, false, null);

    public static EmotionCheckResult none() { return NONE; }

    /** 是否检测到任何情绪风险 */
    public boolean triggered() { return level != EmotionLevel.NONE; }
}
