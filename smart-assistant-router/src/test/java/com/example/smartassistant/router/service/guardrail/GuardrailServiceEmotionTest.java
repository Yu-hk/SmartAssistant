/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.guardrail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * GuardrailService 情绪分级干预测试（对标文章②）。
 */
@ExtendWith(MockitoExtension.class)
class GuardrailServiceEmotionTest {

    @Mock
    private GuardrailProperties properties;

    private GuardrailService guardrailService;

    @BeforeEach
    void setUp() {
        when(properties.isEnabled()).thenReturn(true);
        guardrailService = new GuardrailService(properties);
    }

    @Test
    @DisplayName("普通问题：无情绪风险 → NONE")
    void testNoEmotion() {
        EmotionCheckResult r = guardrailService.checkEmotion("明天北京天气怎么样？");
        assertFalse(r.triggered());
        assertEquals(EmotionLevel.NONE, r.level());
        assertFalse(r.disableTools());
        assertNull(r.guidance());
    }

    @Test
    @DisplayName("焦虑关键词：LIGHT，不禁用工具")
    void testAnxietyLight() {
        EmotionCheckResult r = guardrailService.checkEmotion("我有点焦虑，担心明天的面试");
        assertTrue(r.triggered());
        assertEquals(EmotionLevel.LIGHT, r.level());
        assertTrue(r.categories().contains(EmotionCategory.ANXIETY));
        assertFalse(r.disableTools());
        assertFalse(r.requiresHumanHandoff());
        assertNotNull(r.guidance());
    }

    @Test
    @DisplayName("愤怒关键词：MEDIUM")
    void testAngerMedium() {
        EmotionCheckResult r = guardrailService.checkEmotion("这服务太差了，真是气死我了，烦死了");
        assertEquals(EmotionLevel.MEDIUM, r.level());
        assertTrue(r.categories().contains(EmotionCategory.ANGER));
        assertFalse(r.disableTools());
    }

    @Test
    @DisplayName("抑郁关键词：MEDIUM")
    void testDepressionMedium() {
        EmotionCheckResult r = guardrailService.checkEmotion("最近一直很抑郁，感觉绝望又崩溃");
        assertEquals(EmotionLevel.MEDIUM, r.level());
        assertTrue(r.categories().contains(EmotionCategory.DEPRESSION));
    }

    @Test
    @DisplayName("自伤关键词：HEAVY，禁用工具 + 需人工介入 + 引导话术")
    void testSelfHarmHeavy() {
        EmotionCheckResult r = guardrailService.checkEmotion("我活不下去了，不想活了，想自杀");
        assertEquals(EmotionLevel.HEAVY, r.level());
        assertTrue(r.categories().contains(EmotionCategory.SELF_HARM));
        assertTrue(r.disableTools());
        assertTrue(r.requiresHumanHandoff());
        assertNotNull(r.guidance());
        assertTrue(r.guidance().contains("心理危机干预热线"));
    }

    @Test
    @DisplayName("自伤优先于其他类别：同时含愤怒与自伤 → HEAVY")
    void testSelfHarmDominates() {
        EmotionCheckResult r = guardrailService.checkEmotion("气死我了，干脆不想活了");
        assertEquals(EmotionLevel.HEAVY, r.level());
    }

    @Test
    @DisplayName("护栏关闭时：情绪检测也关闭 → NONE")
    void testDisabled() {
        when(properties.isEnabled()).thenReturn(false);
        GuardrailService svc = new GuardrailService(properties);
        EmotionCheckResult r = svc.checkEmotion("我不想活了");
        assertEquals(EmotionLevel.NONE, r.level());
    }

    @Test
    @DisplayName("空白输入：NONE，不抛异常")
    void testBlank() {
        assertEquals(EmotionLevel.NONE, guardrailService.checkEmotion("   ").level());
        assertEquals(EmotionLevel.NONE, guardrailService.checkEmotion(null).level());
    }
}
