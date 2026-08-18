/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.config;

import com.example.smartassistant.common.model.tier.ChatOptionsMerge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import reactor.core.publisher.Flux;

/**
 * 轻量 LLM 推理通道配置（Router 模块）。
 * <p>
 * 基于 {@link DeepSeekChatModel} 委托模式实现，生产环境直接调用模型 API。
 * 用于关键词提取、任务分解、路由决策、结果合并等辅助任务。
 */
@Configuration
public class LightChatModelConfig {

    private static final Logger log = LoggerFactory.getLogger(LightChatModelConfig.class);

    @Bean
    @Fallback
    @Qualifier("lightChatModel")
    public ChatModel lightChatModel(
            DeepSeekChatModel deepSeekChatModel,
            @Value("${router.light-model.name:${DEEPSEEK_LIGHT_MODEL:deepseek-v4-flash}}") String model,
            @Value("${router.light-model.temperature:0.1}") double temperature) {

        var lightOptions = DeepSeekChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        log.info("[LightChatModel] initialized via DeepSeek API delegation: model={}, temperature={}",
                model, temperature);
        return new LightDelegatingChatModel(deepSeekChatModel, lightOptions);
    }

    private static class LightDelegatingChatModel implements ChatModel {

        private final DeepSeekChatModel delegate;
        private final DeepSeekChatOptions lightOptions;

        LightDelegatingChatModel(DeepSeekChatModel delegate, DeepSeekChatOptions lightOptions) {
            this.delegate = delegate;
            this.lightOptions = lightOptions;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            var lightPrompt = new Prompt(prompt.getInstructions(),
                    ChatOptionsMerge.merge(lightOptions, prompt.getOptions()));
            return delegate.call(lightPrompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            var lightPrompt = new Prompt(prompt.getInstructions(),
                    ChatOptionsMerge.merge(lightOptions, prompt.getOptions()));
            return delegate.stream(lightPrompt);
        }

        public ChatOptions getOptions() {
            return lightOptions;
        }
    }
}
