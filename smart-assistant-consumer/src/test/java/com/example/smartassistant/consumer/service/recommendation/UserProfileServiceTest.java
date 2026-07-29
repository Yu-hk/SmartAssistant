/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.consumer.entity.UserProfile;
import com.example.smartassistant.consumer.service.memory.MemoryVersionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证本次改动的纯逻辑部分：
 * <ul>
 *   <li>P1-B 意图归一化 {@code normalizeIntentTag}：路由 agent 名 → 统一意图类型</li>
 *   <li>P2-A 情绪回流 {@code recordEmotion}：负/正面累加、滑动平均、文件持久化</li>
 * </ul>
 */
class UserProfileServiceTest {

    private UserProfileService service;
    private LLMPreferenceExtractor llm;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        // CALLS_REAL_METHODS：detectLatentSignals 走真实正则逻辑；extract 在需要时单独 stub
        llm = Mockito.mock(LLMPreferenceExtractor.class,
                Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        service = new UserProfileService(llm, new MemoryVersionStore());
        // basePath 默认指向项目 data/users，用临时目录隔离，避免污染工作区
        Field basePathField = UserProfileService.class.getDeclaredField("basePath");
        basePathField.setAccessible(true);
        basePathField.set(service, tempDir.resolve("users").toString());
        this.tempDir = tempDir;
    }

    // ===================================================
    // P1-B 意图归一化
    // ===================================================
    @Test
    void normalizeIntentTag_mapsAllAgentNames() throws Exception {
        Method m = UserProfileService.class.getDeclaredMethod("normalizeIntentTag", String.class);
        m.setAccessible(true);

        assertEquals("order", m.invoke(null, "order"));
        assertEquals("order", m.invoke(null, "ORDER_CHAT"));      // 去后缀 _chat
        assertEquals("order", m.invoke(null, "orders"));
        assertEquals("general", m.invoke(null, "food"));
        assertEquals("general", m.invoke(null, "travel"));
        assertEquals("general", m.invoke(null, "product"));
        assertEquals("general", m.invoke(null, "products"));
        assertEquals("refund", m.invoke(null, "refund"));
        assertEquals("refund", m.invoke(null, "complaint"));
        assertEquals("refund", m.invoke(null, "return"));
        assertEquals("tech", m.invoke(null, "tech"));
        assertEquals("tech", m.invoke(null, "technical"));
        assertEquals("tech", m.invoke(null, "technology"));
        assertEquals("general", m.invoke(null, "general"));
        assertEquals("general", m.invoke(null, new Object[]{null})); // null 安全
        assertEquals("general", m.invoke(null, "unknown_agent")); // 未知 → general
    }

    // ===================================================
    // P2-A 情绪回流
    // ===================================================
    @Test
    void recordEmotion_accumulatesNegativeThenPositive() {
        service.recordEmotion(1L, "急切 / 不满", 30);
        UserProfile p = service.getProfile(1L);
        assertNotNull(p, "首次 recordEmotion 应落盘");
        assertEquals("急切 / 不满", p.getLastEmotionLabel());
        assertEquals(30, p.getLastEmotionScore());
        assertEquals(1, p.getNegativeTouchCount());
        assertEquals(0, p.getPositiveTouchCount());
        assertEquals(1, p.getEmotionTouchCount());
        assertEquals(30.0, p.getEmotionAvgScore(), 0.001);

        service.recordEmotion(1L, "平静", 80);
        p = service.getProfile(1L);
        assertEquals("平静", p.getLastEmotionLabel());
        assertEquals(80, p.getLastEmotionScore());
        assertEquals(1, p.getNegativeTouchCount());  // 负面计数不变
        assertEquals(1, p.getPositiveTouchCount());   // 正面 +1
        assertEquals(2, p.getEmotionTouchCount());
        assertEquals(55.0, p.getEmotionAvgScore(), 0.001); // (30+80)/2
    }

    @Test
    void recordEmotion_persistsAcrossReload() {
        // 模拟"重启"：新建 service 实例，但共享同一临时目录（数据应仍在）
        service.recordEmotion(9L, "愤怒", 15);
        UserProfileService second = new UserProfileService(
                Mockito.mock(LLMPreferenceExtractor.class), new MemoryVersionStore());
        try {
            Field f = UserProfileService.class.getDeclaredField("basePath");
            f.setAccessible(true);
            f.set(second, tempDir.resolve("users").toString());
        } catch (Exception e) {
            fail("无法设置 basePath: " + e.getMessage());
        }
        UserProfile reloaded = second.getProfile(9L);
        assertNotNull(reloaded);
        assertEquals("愤怒", reloaded.getLastEmotionLabel());
        assertEquals(15, reloaded.getLastEmotionScore());
        assertEquals(1, reloaded.getNegativeTouchCount());
    }

    @Test
    void recordEmotion_nullUserIsNoop() {
        assertDoesNotThrow(() -> service.recordEmotion(null, "平静", 50));
    }

    // ===================================================
    // P2-C 隐藏关键信息（潜在需求/隐性信号）
    // ===================================================
    @Test
    void extractAndUpdatePreferences_mergesKeyInsights() {
        // LLM 返回含 keyInsights 的提取结果
        LLMPreferenceExtractor.ExtractedPreferences prefs =
                new LLMPreferenceExtractor.ExtractedPreferences(
                        null, null, List.of(), List.of(), null, List.of(), null,
                        null, null, "normal", List.of(), List.of("企业采购(B2B)"));
        Mockito.when(llm.extract(Mockito.anyString())).thenReturn(prefs);

        // 文本本身也含正则可探测的隐藏信号（亲子场景）
        service.extractAndUpdatePreferences(7L, "给孩子买，公司也想要", null);
        UserProfile p = service.getProfile(7L);
        assertNotNull(p);
        String[] insights = p.getKeyInsightsArray();
        assertNotNull(insights);
        assertTrue(Arrays.asList(insights).contains("企业采购(B2B)"),
                "应保留 LLM 提取的 keyInsight, 实际=" + Arrays.toString(insights));
        assertTrue(Arrays.asList(insights).contains("亲子场景"),
                "应命中正则探测的亲子场景, 实际=" + Arrays.toString(insights));
    }
}
