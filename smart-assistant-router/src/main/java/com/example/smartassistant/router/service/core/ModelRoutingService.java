/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.gateway.llm.AgentLLMGateway;
import com.example.smartassistant.common.gateway.llm.LLMCallConfig;
import com.example.smartassistant.common.gateway.llm.LLMCallResult;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 模型推理服务 — 纯本地推理引擎
 * <p>
 * SmartAssistant 所有推理请求统一通过此服务调用本地 Ollama 模型。
 * 使用 {@link AgentLLMGateway} 统一管理超时、重试、熔断。
 * </p>
 */
@Service
public class ModelRoutingService {

    private static final Logger log = LoggerFactory.getLogger(ModelRoutingService.class);

    private final ChatClient chatClient;
    private final AgentLLMGateway llmGateway;

    public ModelRoutingService(ChatClient.Builder chatClientBuilder,
                               AgentLLMGateway llmGateway,
                               AiChatService aiChatService) {
        this.chatClient = aiChatService.applyAdvisors(chatClientBuilder).build();
        this.llmGateway = llmGateway;
        log.info("[ModelRouting] 纯本地推理引擎初始化完成（Ollama + LLMGateway + 统一Advisor链）");
    }

    /**
     * 调用本地 Ollama 模型推理。
     * <p>
     * 使用 AgentLLMGateway 统一管理超时（30s）、重试（2次）、熔断。
     * </p>
     */
    @Retry(name = "modelRoutingRetry")
    public String call(String systemPrompt, String userMessage) {
        LLMCallConfig config = systemPrompt != null && !systemPrompt.isBlank()
                ? new LLMCallConfig(systemPrompt, 2048, java.time.Duration.ofSeconds(30), 2, 0.5, false)
                : LLMCallConfig.simple();

        LLMCallResult result = llmGateway.call(() -> {
                    var builder = chatClient.prompt().user(userMessage);
                    if (systemPrompt != null && !systemPrompt.isBlank()) {
                        builder.system(systemPrompt);
                    }
                    return builder.call().content();
                },
                "ollama-deepseek-r1:7b",
                config);

        if (result.success()) {
            log.info("[ModelRouting] 推理完成: {} chars, {}ms",
                    result.content() != null ? result.content().length() : 0, result.elapsedMs());
            return result.content();
        }

        log.error("[ModelRouting] 推理失败: {}", result.errorMessage());
        throw new RuntimeException("Ollama model call failed: " + result.errorMessage());
    }

    /**
     * 获取底层 ChatClient（供需要精细控制 prompt 的场景使用）
     */
    public ChatClient getChatClient() {
        return chatClient;
    }
}
