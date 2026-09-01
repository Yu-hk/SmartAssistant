/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import java.util.List;

/**
 * 基于 Spring AI {@link MessageWindowChatMemory} 的内存会话适配器。
 *
 * <p>有界窗口、消息淘汰等通用语义交给 Spring AI 原生中间件；
 * 本类仅保留项目原有 {@link ChatMemory} API 以避免破坏上层调用。
 * 适用于单实例、开发期或会话量较小的场景；生产多实例部署应扩展为 Redis 等分布式实现。</p>
 */
public class InMemoryChatMemory implements ChatMemory {

    private final org.springframework.ai.chat.memory.ChatMemory delegate;

    public InMemoryChatMemory() {
        this(100);
    }

    public InMemoryChatMemory(int maxMessages) {
        this.delegate = MessageWindowChatMemory.builder()
                .maxMessages(Math.max(1, maxMessages))
                .build();
    }

    @Override
    public void add(String conversationId, Message message) {
        if (conversationId == null || message == null) return;
        delegate.add(conversationId, message);
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        if (conversationId == null) return List.of();
        List<Message> list = delegate.get(conversationId);
        if (lastN <= 0 || lastN >= list.size()) {
            return List.copyOf(list);
        }
        return List.copyOf(list.subList(list.size() - lastN, list.size()));
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId != null) delegate.clear(conversationId);
    }
}
