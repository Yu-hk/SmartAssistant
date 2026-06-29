/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

/**
 * 轻量 LLM 推理通道配置。
 * <p>
 * 基于 {@link OllamaChatModel} 委托模式实现，注入 Spring AI 自动配置的原生
 * OllamaChatModel 实例，但通过自定义 {@link OllamaChatOptions} 覆盖默认参数（小模型 +
 * 低温度），实现轻量调用通道。用于：对话摘要、偏好提取、缓存改写、关键词提取、意图识别等辅助任务。
 * <p>
 * 采用委托而非新建 OllamaChatModel 实例，避免重复构造 ToolCallingManager、
 * ObservationRegistry 等复杂依赖。
 */
@Configuration
public class LightChatModelConfig {

    private static final Logger log = LoggerFactory.getLogger(LightChatModelConfig.class);

    @Bean
    @Qualifier("lightChatModel")
    public ChatModel lightChatModel(
            OllamaChatModel ollamaChatModel,
            @Value("${consumer.light-model.name:qwen2.5:3b}") String model,
            @Value("${consumer.light-model.temperature:0.1}") double temperature) {

        var lightOptions = OllamaChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        log.info("[LightChatModel] initialized via delegation: model={}, temperature={}",
                model, temperature);
        return new LightDelegatingChatModel(ollamaChatModel, lightOptions);
    }

    /**
     * 委托 ChatModel 实现。将所有 call/stream 请求转发给底层 OllamaChatModel，
     * 但在每个请求上覆盖默认选项（模型名、温度等），实现"轻量"通道效果。
     */
    private static class LightDelegatingChatModel implements ChatModel {

        private final OllamaChatModel delegate;
        private final OllamaChatOptions lightOptions;

        LightDelegatingChatModel(OllamaChatModel delegate, OllamaChatOptions lightOptions) {
            this.delegate = delegate;
            this.lightOptions = lightOptions;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            var lightPrompt = new Prompt(prompt.getInstructions(), lightOptions);
            return delegate.call(lightPrompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            var lightPrompt = new Prompt(prompt.getInstructions(), lightOptions);
            return delegate.stream(lightPrompt);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return lightOptions;
        }
    }
}
