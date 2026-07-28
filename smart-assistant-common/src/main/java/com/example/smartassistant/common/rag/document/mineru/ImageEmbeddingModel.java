/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.document.mineru;

/**
 * 图像嵌入模型 SPI——将图片字节编码为向量，供多模态检索（PDF 内图片经 MinerU 抽取后向量化）。
 * <p>
 * 默认实现 {@link NoopImageEmbeddingModel} 不可用（返回空数组）；接入真实视觉模型
 * （CLIP / BGE-Vision / 多模态 Embedding 等）时提供 {@code @Primary} Bean 覆盖即可，
 * parser 侧无需改动。
 * </p>
 */
public interface ImageEmbeddingModel {

    /**
     * 图片字节 → 向量。
     *
     * @param imageBytes 图片原始字节（由 MinerU sidecar 抽取并拷贝到 images_dir）
     * @return 向量；模型不可用或失败时返回长度为 0 的数组（调用方据此降级为文本索引）
     */
    float[] embed(byte[] imageBytes);

    /** 模型是否可用（如权重已加载）。不可用返回 false，parser 将跳过图片向量化。 */
    default boolean isAvailable() {
        return true;
    }

    /** 向量维度；不可用返回 0。 */
    int dimension();
}
