/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

/**
 * 图片语义说明策略接口 — 理解图片内容并生成自然语言描述（而非仅抽取文字）。
 * <p>
 * 与 {@link OcrStrategy} 正交：OCR 解决「图里写了什么字」，Caption 解决「这张图在表达什么」
 * （图表含义、实体、布局、截图内容）。二者互补——嵌入图可同时产出 {@code pdf-image-ocr}
 * （文字）与 {@code pdf-image-caption}（语义说明）两类可检索文档。
 * </p>
 */
public interface ImageCaptionStrategy {

    /**
     * 生成图片的语义描述。
     *
     * @param imageData  图片字节数据（PNG/JPEG 均可）
     * @param fileName   原始文件名（用于日志/调试；可为空）
     * @return 描述文本，无内容或不可用时返回 null/blank
     */
    String caption(byte[] imageData, String fileName);

    /**
     * 此策略是否可用（引擎已安装/服务可联通）。
     * 不可用时应降级为 {@link NoopImageCaptionStrategy}。
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 引擎名称标记，用于解析质量指标（如 {@code metadata["pdf.captionEngine"]}）。
     * 默认 "caption"，各实现应覆盖为具体引擎名（ollama / stub）。
     */
    default String engineName() {
        return "caption";
    }
}
