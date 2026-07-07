/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.multimodal;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多模态 RAG 自动装配——按配置提供 {@link ImageCaptioner} Bean。
 * <p>
 * 行为矩阵：
 * <ul>
 *   <li>{@code rag.multimodal.vision.enabled=true} 且存在 {@code ChatModel} Bean
 *       → 装配 {@link OllamaVisionImageCaptioner}（真实视觉描述）；</li>
 *   <li>否则（未启用或无可视觉模型）→ 装配 {@link NoopImageCaptioner}（默认降级）；</li>
 * </ul>
 * 应用侧在 {@code KnowledgeIngestionService} 上调用 {@code setImageCaptioner(bean)}
 * 即可联动多模态摄取。
 * </p>
 */
@Configuration
public class RagMultimodalAutoConfiguration {

    /** 启用视觉描述且存在 ChatModel 时，装配真实描述器 */
    @Bean
    @ConditionalOnProperty(name = "rag.multimodal.vision.enabled", havingValue = "true")
    @ConditionalOnBean(ChatModel.class)
    public ImageCaptioner visionImageCaptioner(ChatModel chatModel) {
        return new OllamaVisionImageCaptioner(chatModel);
    }

    /** 默认 / 降级：空操作描述器（保证 ImageCaptioner Bean 始终存在） */
    @Bean
    @ConditionalOnMissingBean(ImageCaptioner.class)
    public ImageCaptioner noopImageCaptioner() {
        return new NoopImageCaptioner();
    }
}
