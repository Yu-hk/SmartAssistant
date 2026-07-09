/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.guardrail;

/**
 * 用户情绪等级（对标 RAG 生产化文章②：极端情绪分级干预）。
 *
 * <p>等级从低到高：{@code NONE < LIGHT < MEDIUM < HEAVY}。</p>
 * <ul>
 *     <li>{@link #NONE}   无情绪风险</li>
 *     <li>{@link #LIGHT}  轻度负面情绪（烦躁、不安、担心），共情承接即可</li>
 *     <li>{@link #MEDIUM} 中度情绪（愤怒、抑郁、崩溃），暂停任务先疏导</li>
 *     <li>{@link #HEAVY}  重度风险（自伤/伤人倾向），立即安全兜底、禁用工具、人工介入</li>
 * </ul>
 */
public enum EmotionLevel {
    NONE,
    LIGHT,
    MEDIUM,
    HEAVY
}
