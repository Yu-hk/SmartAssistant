/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.multimodal;

/**
 * 空操作图片描述器——默认降级实现，零开销、零外部依赖。
 * <p>
 * 当部署未装配视觉模型（{@code rag.multimodal.vision.enabled=false} 或无
 * {@code ChatModel} Bean）时自动生效，使多模态摄取链路保持可调用但空操作。
 * </p>
 */
public class NoopImageCaptioner implements ImageCaptioner {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String caption(ImageReference image) {
        return "";
    }
}
