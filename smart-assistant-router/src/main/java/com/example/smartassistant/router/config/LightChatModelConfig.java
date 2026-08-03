/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

/**
 * 轻量 LLM 推理通道配置（Router 模块）。
 * <p>
 * 基于 {@link ChatModel} 委托模式实现。用于关键词提取、任务分解、路由决策、
 * 结果合并等辅助任务。
 */
@Configuration
public class LightChatModelConfig {

    private static final Logger log = LoggerFactory.getLogger(LightChatModelConfig.class);

    @Bean(defaultCandidate = false)
    @Qualifier("lightChatModel")
    public ChatModel lightChatModel(
            @Qualifier("deepSeekChatModel") ChatModel apiChatModel,
            @Value("${router.light-model.name:deepseek-v4-flash}") String model,
            @Value("${router.light-model.temperature:0.1}") double temperature) {

        var lightOptions = ChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        log.info("[LightChatModel] initialized via delegation: model={}, temperature={}",
                model, temperature);
        return new LightDelegatingChatModel(apiChatModel, lightOptions);
    }

    private static class LightDelegatingChatModel implements ChatModel {

        private final ChatModel delegate;
        private final ChatOptions lightOptions;

        LightDelegatingChatModel(ChatModel delegate, ChatOptions lightOptions) {
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

        public ChatOptions getOptions() {
            return lightOptions;
        }
    }
}
