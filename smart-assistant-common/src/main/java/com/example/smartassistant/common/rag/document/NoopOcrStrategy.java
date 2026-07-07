/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import java.util.List;

/**
 * 空操作 OCR 策略 — 当无可用 OCR 引擎时的默认降级。
 * <p>
 * 始终返回空文本列表，不对图片做任何提取。
 * 当部署 Tesseract 或接入外部 OCR 服务后，替换为真实 {@link OcrStrategy} 实现。
 * </p>
 */
public class NoopOcrStrategy implements OcrStrategy {

    @Override
    public List<String> extractText(byte[] imageData, String fileName) {
        return List.of();
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
