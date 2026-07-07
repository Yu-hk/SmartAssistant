/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

/**
 * OCR 策略工厂 — 按运行环境自动选择最优可用的 {@link OcrStrategy}。
 * <p>
 * 优先探测系统 Tesseract（真实引擎）；不可用时降级为 {@link NoopOcrStrategy}。
 * 解析器（如 {@link PdfDocumentParser}）默认使用 {@link #autoDetect()}，
 * 使 OCR 能力在部署环境装好 Tesseract 后自动生效，无需改代码。
 * </p>
 */
public final class OcrStrategies {

    private OcrStrategies() {}

    /**
     * 自动选择 OCR 策略：Tesseract 可用则返回其实例，否则返回空操作降级。
     *
     * @param languages Tesseract 语言参数（如 "eng"、"chi_sim+eng"）；可为 null（默认 eng）
     */
    public static OcrStrategy autoDetect(String languages) {
        TesseractOcrStrategy tesseract = new TesseractOcrStrategy(languages);
        return tesseract.isAvailable() ? tesseract : new NoopOcrStrategy();
    }

    /** 使用默认语言（eng）自动检测 */
    public static OcrStrategy autoDetect() {
        return autoDetect("eng");
    }
}
