/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import java.util.List;

/**
 * OCR 策略接口 — 从图片字节数据中提取文本。
 * <p>
 * 设计为可插拔策略模式，允许接入不同 OCR 引擎：
 * <ul>
 *   <li>本地 Tesseract OCR（默认降级为 {@link NoopOcrStrategy}）</li>
 *   <li>云端 OCR 服务（接入前请注意数据合规）</li>
 *   <li>LLM 视觉模型 OCR（Ollama 多模态模型）</li>
 * </ul>
 * </p>
 */
public interface OcrStrategy {

    /**
     * 从图片数据中提取文本。
     *
     * @param imageData  图片字节数据（PNG/JPEG/TIFF 均可）
     * @param fileName   原始文件名（用于日志/调试；可为空）
     * @return 提取到的文本段落，无内容返回空列表
     */
    List<String> extractText(byte[] imageData, String fileName);

    /**
     * 此策略是否可用（引擎已安装/服务可联通）。
     * 不可用时应降级为 {@link NoopOcrStrategy}。
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 引擎名称标记，用于解析质量指标（如 {@code metadata["pdf.ocrEngine"]}）。
     * 默认 "ocr"，各实现应覆盖为具体引擎名（tesseract / ollama）。
     */
    default String engineName() {
        return "ocr";
    }
}
