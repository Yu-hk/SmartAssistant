/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.multimodal;

/**
 * 图片描述器接口——把图片转换为可入库的文本描述（视觉理解）。
 * <p>
 * 实现可插拔：{@link NoopImageCaptioner}（默认降级，零开销）、
 * {@link OllamaVisionImageCaptioner}（基于 Spring AI 多模态 ChatModel）。
 * 部署侧未配置视觉模型时自动降级为 Noop，不影响文本 RAG 链路。
 * </p>
 */
public interface ImageCaptioner {

    /**
     * @return 当前描述器是否可用（如视觉模型是否就绪）
     */
    boolean isAvailable();

    /**
     * 对单张图片生成文本描述。
     *
     * @param image 图片引用（非空）
     * @return 描述文本；不可用 / 图片为空 / 描述失败均返回空串（不抛异常）
     */
    String caption(ImageReference image);
}
