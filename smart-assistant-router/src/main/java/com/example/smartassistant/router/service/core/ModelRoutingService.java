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
 * 模型推理服务 — DeepSeek API 推理引擎
 * <p>
 * SmartAssistant 的路由推理请求统一通过此服务调用 DeepSeek API。
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
        log.info("[ModelRouting] DeepSeek API 推理引擎初始化完成（LLMGateway + 统一Advisor链）");
    }

    /**
     * 调用 DeepSeek API 模型推理。
     * <p>
     * 使用 AgentLLMGateway 统一管理超时和熔断。路由只需要结构化分类结果，采用 60s 单次超时，
     * 不叠加重试，避免一次请求被放大为数分钟等待。
     * </p>
     */
    @Retry(name = "modelRoutingRetry")
    public String call(String systemPrompt, String userMessage) {
        LLMCallConfig config = new LLMCallConfig(
                systemPrompt,
                256,
                java.time.Duration.ofSeconds(60),
                0,
                0.5,
                false);

        LLMCallResult result = llmGateway.call(() -> {
                    var builder = chatClient.prompt().user(userMessage);
                    if (systemPrompt != null && !systemPrompt.isBlank()) {
                        builder.system(systemPrompt);
                    }
                    return builder.call().content();
                },
                "deepseek-v4-flash",
                config);

        if (result.success()) {
            log.info("[ModelRouting] 推理完成: {} chars, {}ms",
                    result.content() != null ? result.content().length() : 0, result.elapsedMs());
            return result.content();
        }

        log.error("[ModelRouting] 推理失败: {}", result.errorMessage());
        throw new RuntimeException("Model API call failed: " + result.errorMessage());
    }

    /**
     * 获取底层 ChatClient（供需要精细控制 prompt 的场景使用）
     */
    public ChatClient getChatClient() {
        return chatClient;
    }
}
