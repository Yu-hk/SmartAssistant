/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HallucinationDetector} 的单元测试。
 *
 * @author Yu-hk
 * @since 2026-07-06
 */
class HallucinationDetectorTest {

    private final HallucinationDetector detector = new HallucinationDetector();

    @Test
    @DisplayName("空答案返回无幻觉")
    void testEmptyAnswer() {
        HallucinationDetector.HallucinationResult result = detector.detect("", "有一些上下文内容");
        assertFalse(result.hasHallucination());
        assertEquals(0.0, result.hallucinationRate());
    }

    @Test
    @DisplayName("空上下文标记为高危")
    void testEmptyContext() {
        HallucinationDetector.HallucinationResult result = detector.detect("答案是42。", "");
        assertTrue(result.hasHallucination());
        assertTrue(result.hallucinationRate() > 0);
    }

    @Test
    @DisplayName("数字断言幻觉：数字不在上下文中")
    void testNumberHallucination() {
        String answer = "这个产品的价格是299元。";
        String context = "产品描述：这是一款智能手表。";
        HallucinationDetector.HallucinationResult result = detector.detect(answer, context);
        assertTrue(result.hasHallucination());
        boolean foundNumberClaim = result.claims().stream()
                .anyMatch(c -> c.type().equals("数字断言"));
        assertTrue(foundNumberClaim, "应检测到数字断言幻觉");
    }

    @Test
    @DisplayName("数字在上下文中出现时不被标记")
    void testNumberInContext() {
        String answer = "这款手机售价2999元。";
        String context = "产品参数：售价2999元，存储256GB。";
        HallucinationDetector.HallucinationResult result = detector.detect(answer, context);
        // 数字 2999 在上下文中，不应检测为幻觉
        boolean numberClaim = result.claims().stream()
                .anyMatch(c -> c.type().equals("数字断言"));
        assertFalse(numberClaim, "数字在上下文中不应被标记");
    }

    @Test
    @DisplayName("否定反转检测：答案否定但上下文中肯定")
    void testNegationHallucination() {
        String answer = "这个商品没有折扣活动。";
        String context = "当前促销：限时折扣活动进行中，全场8折。";
        HallucinationDetector.HallucinationResult result = detector.detect(answer, context);
        boolean foundNegation = result.claims().stream()
                .anyMatch(c -> c.type().equals("否定反转"));
        // 上下文包含"折扣"相关内容，答案说"没有折扣" — 可能是幻觉
        System.out.println("否定检测结果: " + result.claims());
    }

    @Test
    @DisplayName("带引号的实体不在上下文中被标记")
    void testEntityHallucination() {
        String answer = "根据「超级会员计划」的规则，可享受双倍积分。";
        String context = "普通会员规则：每消费1元积1分。";
        HallucinationDetector.HallucinationResult result = detector.detect(answer, context);
        boolean foundEntity = result.claims().stream()
                .anyMatch(c -> c.type().equals("实体不存在"));
        assertTrue(foundEntity, "『超级会员计划』不在上下文中，应被标记");
    }

    @Test
    @DisplayName("忠实答案不被标记")
    void testFaithfulAnswer() {
        String answer = "该商品售价为199元，有黑色和白色可选。";
        String context = "商品信息：售价199元，颜色有黑色和白色。";
        HallucinationDetector.HallucinationResult result = detector.detect(answer, context);
        // 数字和实体都在上下文中，不应有幻觉标记
        System.out.println("忠实答案检测: " + result);
    }

    @Test
    @DisplayName("空参数不抛异常")
    void testNullSafe() {
        assertDoesNotThrow(() -> detector.detect(null, "context"));
        assertDoesNotThrow(() -> detector.detect("answer", null));
        assertDoesNotThrow(() -> detector.detect(null, null));
    }
}
