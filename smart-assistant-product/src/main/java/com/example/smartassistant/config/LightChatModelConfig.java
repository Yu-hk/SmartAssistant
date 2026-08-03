/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 */
package com.example.smartassistant.config;

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
 * DeepSeek API model for high-frequency helper tasks such as memory extraction.
 */
@Configuration
public class LightChatModelConfig {

    @Bean(defaultCandidate = false)
    @Qualifier("lightChatModel")
    public ChatModel lightChatModel(
            @Qualifier("deepSeekChatModel") ChatModel apiChatModel,
            @Value("${product.light-model.name:deepseek-v4-flash}") String model,
            @Value("${product.light-model.temperature:0.1}") double temperature) {
        ChatOptions options = ChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();
        return new OptionsChatModel(apiChatModel, options);
    }

    private record OptionsChatModel(ChatModel delegate, ChatOptions options) implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return delegate.call(new Prompt(prompt.getInstructions(), options));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return delegate.stream(new Prompt(prompt.getInstructions(), options));
        }
    }
}
