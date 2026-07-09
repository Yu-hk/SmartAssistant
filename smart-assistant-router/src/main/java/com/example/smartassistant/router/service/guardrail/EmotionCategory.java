/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.guardrail;

/**
 * 情绪类别。
 *
 * <p>用于区分用户当前的主导情绪维度，便于分级干预时选择话术与处置策略。</p>
 */
public enum EmotionCategory {
    /** 焦虑：担心、紧张、害怕、不安 */
    ANXIETY,
    /** 愤怒：气愤、恼火、暴躁 */
    ANGER,
    /** 抑郁：绝望、崩溃、空虚、孤独 */
    DEPRESSION,
    /** 自伤/伤人倾向：最高风险，需立即安全兜底 */
    SELF_HARM
}
