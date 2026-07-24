/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

/**
 * 空操作图片说明策略 — 引擎不可用时降级。
 * {@link #isAvailable()} 永远返回 false，{@link #caption} 返回 null，绝不产出任何说明文档，
 * 也不阻断解析。
 */
public class NoopImageCaptionStrategy implements ImageCaptionStrategy {

    @Override
    public String caption(byte[] imageData, String fileName) {
        return null;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String engineName() {
        return "noop";
    }
}
