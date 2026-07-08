/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.model.tier;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 选项覆盖型委托 ChatModel——在委托目标上强制套用指定的 {@link ChatOptions}（模型名/温度等），
 * 使同一个底层 {@link ChatModel} 可被不同档位以不同参数复用。
 *
 * <p>与 Router 模块 {@code LightChatModelConfig.LightDelegatingChatModel} 同构，
 * 但沉淀到 common 供 {@link TierModelAutoConfiguration} 三档统一使用，避免重复实现。</p>
 */
class DelegatingOptionsChatModel implements ChatModel {

    private final ChatModel delegate;
    private final ChatOptions options;

    DelegatingOptionsChatModel(ChatModel delegate, ChatOptions options) {
        this.delegate = delegate;
        this.options = options;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(new Prompt(prompt.getInstructions(), options));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(new Prompt(prompt.getInstructions(), options));
    }

    @Override
    public ChatOptions getOptions() {
        return options;
    }
}
