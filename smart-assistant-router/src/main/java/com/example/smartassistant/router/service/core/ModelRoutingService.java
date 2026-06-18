/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 模型推理服务 — 纯本地推理引擎
 * <p>
 * SmartAssistant 所有推理请求统一通过此服务调用本地 Ollama 模型。
 * 主推理模型：deepseek-r1:7b（由 application.yml 配置）
 * 备选推理模型：qwen2.5:7b（改配置即可切换）
 * <p>
 * 无云端依赖，所有数据不出本机。
 */
@Service
public class ModelRoutingService {

    private static final Logger log = LoggerFactory.getLogger(ModelRoutingService.class);

    private final ChatClient chatClient;

    public ModelRoutingService(ChatClient.Builder chatClientBuilder) {
        // ⭐ Ollama 为 @Primary ChatModel，ChatClient.Builder 自动注入
        this.chatClient = chatClientBuilder.build();
        log.info("[ModelRouting] 纯本地推理引擎初始化完成（Ollama）");
    }

    /**
     * 调用本地 Ollama 模型推理。
     * <p>
     * 使用 Resilience4j 指数退避重试：
     * 200ms → 400ms → 800ms，最多 3 次。
     * </p>
     */
    @Retry(name = "modelRoutingRetry")
    public String call(String systemPrompt, String userMessage) {
        long start = System.currentTimeMillis();
        try {
            String reply = chatClient.prompt()
                    .system(systemPrompt != null ? systemPrompt : "")
                    .user(userMessage)
                    .call()
                    .content();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[ModelRouting] 推理完成: {} chars, {}ms",
                    reply != null ? reply.length() : 0, elapsed);
            return reply;
        } catch (Exception e) {
            log.error("[ModelRouting] 推理失败: {}", e.getMessage());
            throw new RuntimeException("Ollama local model call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 获取底层 ChatClient（供需要精细控制 prompt 的场景使用）
     */
    public ChatClient getChatClient() {
        return chatClient;
    }
}
