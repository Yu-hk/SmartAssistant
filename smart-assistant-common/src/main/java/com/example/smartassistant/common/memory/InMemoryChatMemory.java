/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的 {@link ChatMemory} 实现（默认实现，零外部依赖）。
 *
 * <p>有界环形缓冲：超过 {@code maxMessages} 时淘汰最旧消息，避免无限增长。
 * 适用于单实例、开发期或会话量较小的场景；生产多实例部署应扩展为 Redis 等分布式实现。</p>
 */
public class InMemoryChatMemory implements ChatMemory {

    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();
    private final int maxMessages;

    public InMemoryChatMemory() {
        this(100);
    }

    public InMemoryChatMemory(int maxMessages) {
        this.maxMessages = Math.max(1, maxMessages);
    }

    @Override
    public void add(String conversationId, Message message) {
        if (conversationId == null || message == null) return;
        List<Message> list = store.computeIfAbsent(conversationId, k -> new ArrayList<>());
        list.add(message);
        while (list.size() > maxMessages) {
            list.remove(0);
        }
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        List<Message> list = store.getOrDefault(conversationId, List.of());
        if (lastN <= 0 || lastN >= list.size()) {
            return List.copyOf(list);
        }
        return List.copyOf(list.subList(list.size() - lastN, list.size()));
    }

    @Override
    public void clear(String conversationId) {
        store.remove(conversationId);
    }
}
