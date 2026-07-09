/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.evaluation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CorrectionSignalDetector 纯单测（确定性，无 LLM / Redis 依赖）。
 */
class CorrectionSignalDetectorTest {

    @Test
    void detectsExplicitCorrection() {
        assertTrue(CorrectionSignalDetector.isCorrection("你刚才说错了，应该是上海"));
        assertTrue(CorrectionSignalDetector.isCorrection("不对，我指的是北京"));
        assertTrue(CorrectionSignalDetector.isCorrection("我记得上次你说深圳，其实应该是广州"));
        assertTrue(CorrectionSignalDetector.isCorrection("纠正一下，订单号是 12345 不是 67890"));
    }

    @Test
    void detectsRefuteOfPriorAnswer() {
        assertTrue(CorrectionSignalDetector.isCorrection("你搞错了，不是这个套餐"));
        assertTrue(CorrectionSignalDetector.isCorrection("前面说错了，准确地说应该是明天"));
        assertTrue(CorrectionSignalDetector.isCorrection("我表达错了，我的意思是杭州"));
    }

    @Test
    void ignoresNormalQuery() {
        assertFalse(CorrectionSignalDetector.isCorrection("帮我查一下北京天气"));
        assertFalse(CorrectionSignalDetector.isCorrection("推荐几个适合周末去的餐厅"));
        assertFalse(CorrectionSignalDetector.isCorrection("我的订单到哪了"));
    }

    @Test
    void ignoresPureAgreement() {
        assertFalse(CorrectionSignalDetector.isCorrection("你说得对，就是这样"));
        assertFalse(CorrectionSignalDetector.isCorrection("确实如此，没问题"));
    }

    @Test
    void emptyOrNullReturnsFalse() {
        assertFalse(CorrectionSignalDetector.isCorrection(null));
        assertFalse(CorrectionSignalDetector.isCorrection("   "));
    }

    @Test
    void detectReturnsMarkersForAudit() {
        var signal = CorrectionSignalDetector.detect("你搞错了，应该是上海");
        assertTrue(signal.isCorrection());
        assertTrue(signal.getMarkers().contains("你搞错了"));
        assertTrue(signal.getMarkers().contains("应该是"));
    }
}
