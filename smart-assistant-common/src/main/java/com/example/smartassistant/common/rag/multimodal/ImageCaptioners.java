/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.multimodal;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 图片描述器工厂——按运行环境自动选择实现。
 * <p>
 * 提供方：
 * <ul>
 *   <li>{@link #autoDetect(ChatModel)} — 有视觉模型则返回 {@link OllamaVisionImageCaptioner}，
 *       否则降级 {@link NoopImageCaptioner}；</li>
 *   <li>{@link #noop()} — 显式获取空操作实现。</li>
 * </ul>
 */
public final class ImageCaptioners {

    private ImageCaptioners() {
    }

    /**
     * 自动检测：传入视觉模型返回真实描述器，否则降级 Noop。
     */
    public static ImageCaptioner autoDetect(ChatModel visionModel) {
        if (visionModel == null) {
            return new NoopImageCaptioner();
        }
        return new OllamaVisionImageCaptioner(visionModel);
    }

    /**
     * 显式空操作描述器。
     */
    public static ImageCaptioner noop() {
        return new NoopImageCaptioner();
    }
}
