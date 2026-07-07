/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 统一对话记忆抽象 — 对标 Spring AI 原生 {@code ChatMemory}，但为项目自研适配层。
 *
 * <p>项目原有记忆为分散实现（{@code EntityProfileService} Redis 画像 + {@code *MemoryTool} 工具化 +
 * 摘要压缩）。本接口提供统一的记忆读写契约，便于后续将自研记忆收敛到统一抽象，或接入 Spring AI
 * 原生持久化后端（如 CassandraChatMemory）。当前提供 {@link InMemoryChatMemory} 实现，
 * Redis 实现可基于 {@code StringRedisTemplate} 扩展（需注意 {@code Message} 具体子类的序列化）。</p>
 */
public interface ChatMemory {

    /** 追加一条消息到指定会话 */
    void add(String conversationId, Message message);

    /** 获取指定会话最近 lastN 条消息（lastN<=0 表示全部） */
    List<Message> get(String conversationId, int lastN);

    /** 清空指定会话记忆 */
    void clear(String conversationId);
}
