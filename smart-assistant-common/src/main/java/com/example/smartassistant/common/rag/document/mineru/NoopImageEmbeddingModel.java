/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.document.mineru;

/**
 * {@link ImageEmbeddingModel} 默认实现——不可用（无视觉模型时）。
 * <p>返回空数组、{@link #isAvailable()} 返回 false，使 parser 安全地跳过图片向量化、
 * 退化为仅索引 caption/OCR 文本，行为与未开启 {@code enabledImageVectorization} 一致。</p>
 */
public class NoopImageEmbeddingModel implements ImageEmbeddingModel {

    @Override
    public float[] embed(byte[] imageBytes) {
        return new float[0];
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public int dimension() {
        return 0;
    }
}
