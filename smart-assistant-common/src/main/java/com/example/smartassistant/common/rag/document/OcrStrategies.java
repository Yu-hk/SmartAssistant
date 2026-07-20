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
 * 优先探测系统 Tesseract（真实引擎，默认语言 {@code chi_sim+eng} 以覆盖中文场景）；
 * 不可用时降级为 {@link NoopOcrStrategy}。
 * 解析器（如 {@link PdfDocumentParser}）默认使用 {@link #autoDetect()}，
 * 使 OCR 能力在部署环境装好 Tesseract 后自动生效，无需改代码。
 * </p>
 *
 * <p>另提供 {@link #ollama()} 备选：本地多模态视觉模型（Ollama）OCR，
 * 适合无 Tesseract 但已部署 Ollama 的场景，零额外 Java 依赖。</p>
 */
public final class OcrStrategies {

    /** Tesseract 默认语言：中文优先（简体中文 + 英文），覆盖绝大多数中文文档场景 */
    public static final String DEFAULT_LANGUAGES = "chi_sim+eng";

    private OcrStrategies() {}

    /**
     * 自动选择 OCR 策略：Tesseract 可用则返回其实例，否则返回空操作降级。
     *
     * @param languages Tesseract 语言参数（如 "eng"、"chi_sim+eng"）；为 null 时使用 {@link #DEFAULT_LANGUAGES}
     */
    public static OcrStrategy autoDetect(String languages) {
        TesseractOcrStrategy tesseract = new TesseractOcrStrategy(languages);
        return tesseract.isAvailable() ? tesseract : new NoopOcrStrategy();
    }

    /** 使用默认语言（{@value #DEFAULT_LANGUAGES}）自动检测 */
    public static OcrStrategy autoDetect() {
        return autoDetect(DEFAULT_LANGUAGES);
    }

    /**
     * 构建基于本地 Ollama 多模态视觉模型的 OCR 策略（默认端点与模型）。
     * 仅当 Ollama 服务可联通且模型已拉取时才可用，否则 {@link #isAvailable()} 返回 false。
     */
    public static OcrStrategy ollama() {
        return new OllamaVisionOcrStrategy();
    }
}
