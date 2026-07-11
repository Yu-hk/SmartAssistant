/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.audit;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 用量缓存（零 Spring 装配，静态内存结构）。
 *
 * <p>以 requestId 为 key，临时存储最近一次 LLM 调用的 token 用量。
 * {@code TokenUsageAdvisor} 在发布 {@link AiAuditEvent} 的同时写入此处，
 * {@code ChatController} / {@code StreamChatController} 在构建 SSE 事件时从此处读取。
 * 相当于一个轻量的「每请求 Token 用量共享缓冲区」。</p>
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li>单机内存，不跨实例共享</li>
 *   <li>仅保留最新一次用量（同一 requestId 多次调用时覆盖）</li>
 *   <li>不自动清理（requestId 是 UUID，有限生命周期）</li>
 * </ul>
 */
public final class TokenUsageCache {

    private static final ConcurrentHashMap<String, TokenUsage> CACHE = new ConcurrentHashMap<>();

    private TokenUsageCache() {}

    /** 记录一次 token 用量 */
    public static void record(String requestId, int promptTokens, int completionTokens, int totalTokens) {
        if (requestId == null || requestId.isBlank()) return;
        CACHE.put(requestId, new TokenUsage(promptTokens, completionTokens, totalTokens));
    }

    /** 读取并删除指定 requestId 的 token 用量；不存在时返回 {@code null} */
    public static TokenUsage consume(String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        return CACHE.remove(requestId);
    }

    /** Token 用量值对象 */
    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {}
}
