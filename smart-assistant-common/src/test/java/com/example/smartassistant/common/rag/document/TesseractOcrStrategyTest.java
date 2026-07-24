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

import static org.junit.jupiter.api.Assertions.*;

class TesseractOcrStrategyTest {

    @Test
    @DisplayName("构造与检测不抛异常；不可用时 extractText 返回空列表")
    void testUnavailableDegradesGracefully() {
        TesseractOcrStrategy strategy = new TesseractOcrStrategy();
        // 本开发/CI 环境通常未安装 tesseract，应降级为不可用
        if (!strategy.isAvailable()) {
            List<String> result = strategy.extractText(new byte[]{1, 2, 3, 4}, "scan.png");
            assertTrue(result.isEmpty(), "不可用时不应返回文本");
        } else {
            // 若环境装了 tesseract，至少保证调用不抛异常
            assertDoesNotThrow(() -> strategy.extractText(new byte[0], "empty.png"));
        }
    }

    @Test
    @DisplayName("空输入直接返回空列表，不触发进程")
    void testEmptyInput() {
        TesseractOcrStrategy strategy = new TesseractOcrStrategy();
        assertEquals(List.of(), strategy.extractText(null, "x.png"));
        assertEquals(List.of(), strategy.extractText(new byte[0], "x.png"));
    }

    @Test
    @DisplayName("OcrStrategies.autoDetect 始终返回非 null 策略（Tesseract 不可用则降级为 Noop）")
    void testAutoDetectNeverNull() {
        OcrStrategy strategy = OcrStrategies.autoDetect();
        assertNotNull(strategy);
        assertDoesNotThrow(() -> strategy.extractText(new byte[]{9}, "img.png"));
    }

    @Test
    @DisplayName("engineName 标记为 tesseract")
    void testEngineName() {
        assertEquals("tesseract", new TesseractOcrStrategy().engineName());
    }

    @Test
    @DisplayName("显式指定不存在的二进制路径：优雅降级为不可用，extractText 返回空")
    void testExplicitMissingBinaryDegrades() {
        String bogus = "C:/nonexistent/path/tesseract-xyz.exe";
        // 确保该路径确实不存在
        java.io.File f = new java.io.File(bogus);
        org.junit.jupiter.api.Assumptions.assumeTrue(!f.exists(), "意外存在，跳过");
        System.setProperty("sa.ocr.tesseract.bin", bogus);
        try {
            TesseractOcrStrategy strategy = new TesseractOcrStrategy();
            assertFalse(strategy.isAvailable(), "无效二进制路径应判定为不可用");
            assertEquals(List.of(), strategy.extractText(new byte[]{1}, "x.png"));
        } finally {
            System.clearProperty("sa.ocr.tesseract.bin");
        }
    }
}
