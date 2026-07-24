/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OllamaVisionOcrStrategy 测试（G1 备选 OCR）。
 * <p>核心验证优雅降级：无 Ollama / 不可达时 {@code isAvailable} 返回 false，
 * {@code extractText} 返回空列表且不抛异常。</p>
 */
class OllamaVisionOcrStrategyTest {

    @Test
    @DisplayName("默认构造：本机无 Ollama 时 isAvailable=false 且 extractText 返回空（不抛异常）")
    void defaultUnavailableDegradesGracefully() {
        OllamaVisionOcrStrategy s = new OllamaVisionOcrStrategy();
        if (!s.isAvailable()) {
            assertEquals("ollama", s.engineName());
            assertTrue(s.extractText(new byte[]{1, 2, 3}, "img.png").isEmpty(),
                    "不可用时不应返回文本");
        } else {
            // 若本机恰运行 Ollama，仅验证调用不抛异常
            assertDoesNotThrow(() -> s.extractText(new byte[0], "empty.png"));
        }
    }

    @Test
    @DisplayName("指向不可达端点：isAvailable=false，extractText 返回空且不抛异常")
    void unreachableEndpointDegrades() {
        OllamaVisionOcrStrategy s = new OllamaVisionOcrStrategy(
                "http://127.0.0.1:9", "llava", "extract text");
        assertFalse(s.isAvailable(), "不可达端点应判定为不可用");
        assertTrue(s.extractText(new byte[]{9, 9}, "x.png").isEmpty(),
                "不可达时 extractText 应返回空列表");
    }

    @Test
    @DisplayName("自定义端点与默认引擎名")
    void customEndpointAndEngineName() {
        OllamaVisionOcrStrategy s = new OllamaVisionOcrStrategy(
                "http://localhost:11434", "my-model", "p");
        assertEquals("ollama", s.engineName());
        assertDoesNotThrow(() -> s.extractText(null, "x.png"));
    }
}
