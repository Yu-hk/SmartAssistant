/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 管线自动配置。
 *
 * <p>提供 {@link RagSearchPipeline} Bean，由各模块的 Handler
 * 通过 {@link org.springframework.stereotype.Component} 自动注入。
 */
@Configuration
public class RagPipelineAutoConfiguration {

    @Bean
    public RagSearchPipeline ragSearchPipeline(java.util.List<RagSearchHandler> handlers) {
        return new RagSearchPipeline(handlers);
    }
}
