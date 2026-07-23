/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OllamaVisionCaptionStrategy 测试（图片语义说明）。
 * <p>核心验证优雅降级：无 Ollama / 不可达时 {@code isAvailable} 返回 false，
 * {@code caption} 返回 null 且不抛异常。</p>
 */
class OllamaVisionCaptionStrategyTest {

    @Test
    @DisplayName("默认构造：本机无 Ollama 时 isAvailable=false 且 caption 返回 null（不抛异常）")
    void defaultUnavailableDegradesGracefully() {
        OllamaVisionCaptionStrategy s = new OllamaVisionCaptionStrategy();
        if (!s.isAvailable()) {
            assertEquals("ollama", s.engineName());
            assertNull(s.caption(new byte[]{1, 2, 3}, "img.png"), "不可用时不应返回说明");
        } else {
            // 若本机恰运行 Ollama，仅验证调用不抛异常
            assertDoesNotThrow(() -> s.caption(new byte[0], "empty.png"));
        }
    }

    @Test
    @DisplayName("指向不可达端点：isAvailable=false，caption 返回 null 且不抛异常")
    void unreachableEndpointDegrades() {
        OllamaVisionCaptionStrategy s = new OllamaVisionCaptionStrategy(
                "http://127.0.0.1:9", "llava", "describe image");
        assertFalse(s.isAvailable(), "不可达端点应判定为不可用");
        assertNull(s.caption(new byte[]{9, 9}, "x.png"), "不可达时 caption 应返回 null");
    }

    @Test
    @DisplayName("自定义端点与默认引擎名")
    void customEndpointAndEngineName() {
        OllamaVisionCaptionStrategy s = new OllamaVisionCaptionStrategy(
                "http://localhost:11434", "my-model", "p");
        assertEquals("ollama", s.engineName());
        assertDoesNotThrow(() -> s.caption(null, "x.png"));
    }
}
