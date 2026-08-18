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
import com.example.smartassistant.common.model.tier.ModelTier;
import com.example.smartassistant.common.model.tier.TierModelRegistry;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * DeepSeek 模型推理服务。
 * <p>
 * 意图拆解按当前用户问题长度选择模型：短请求使用低延迟的
 * {@code deepseek-v4-flash}，长请求使用推理能力更强的
 * {@code deepseek-v4-pro}。调用仍由 {@link AgentLLMGateway}
 * 统一管理超时、重试和熔断。
 * </p>
 */
@Service
public class ModelRoutingService {

    private static final Logger log = LoggerFactory.getLogger(ModelRoutingService.class);

    private final ChatClient chatClient;
    private final String lightIntentModel;
    private final String heavyIntentModel;
    private final AgentLLMGateway llmGateway;
    private final DeepSeekPlanningClient planningClient;

    @Value("${router.task-analysis.pro-min-chars:${router.task-analysis.reasoner-min-chars:160}}")
    private int proMinChars = 160;

    @Value("${router.task-analysis.pro-timeout-ms:35000}")
    private long proTimeoutMs = 35_000L;

    @Value("${router.task-analysis.flash-timeout-ms:30000}")
    private long flashTimeoutMs = 30_000L;

    public ModelRoutingService(ChatClient.Builder chatClientBuilder,
                               AgentLLMGateway llmGateway,
                               AiChatService aiChatService,
                               ObjectProvider<TierModelRegistry> tierModelRegistryProvider,
                               DeepSeekPlanningClient planningClient) {
        this.chatClient = aiChatService.applyAdvisors(chatClientBuilder).build();
        this.llmGateway = llmGateway;
        this.planningClient = planningClient;

        TierModelRegistry registry = tierModelRegistryProvider.getIfAvailable();
        if (registry != null && registry.has(ModelTier.LIGHT) && registry.has(ModelTier.HEAVY)) {
            this.lightIntentModel = registry.modelName(ModelTier.LIGHT);
            this.heavyIntentModel = registry.modelName(ModelTier.HEAVY);
        } else {
            this.lightIntentModel = "deepseek-v4-flash";
            this.heavyIntentModel = "deepseek-v4-flash";
            log.warn("[ModelRouting] TierModelRegistry 不可用，意图分析暂统一使用 deepseek-v4-flash");
        }
        log.info("[ModelRouting] DeepSeek 意图模型就绪: short={}, long={}",
                lightIntentModel, heavyIntentModel);
    }

    /**
     * 根据当前问题长度选择 DeepSeek 模型并执行结构化意图拆解。
     */
    public IntentModelResponse callForIntent(String systemPrompt, String userMessage,
                                             String currentQuestion) {
        ModelTier tier = selectIntentTier(currentQuestion, proMinChars);
        String selectedModel = tier == ModelTier.HEAVY ? heavyIntentModel : lightIntentModel;
        long timeoutMs = tier == ModelTier.HEAVY ? proTimeoutMs : flashTimeoutMs;
        LLMCallResult result = callIntentModel(
                selectedModel, systemPrompt, userMessage, timeoutMs);

        if (!result.success()) {
            throw new RuntimeException("DeepSeek intent analysis failed: " + result.errorMessage());
        }
        int chars = codePointLength(currentQuestion);
        log.info("[ModelRouting] 意图拆解完成: chars={}, tier={}, model={}, elapsed={}ms",
                chars, tier.getCode(), selectedModel, result.elapsedMs());
        return new IntentModelResponse(result.content(), selectedModel, tier.getCode(),
                chars, result.elapsedMs());
    }

    private LLMCallResult callIntentModel(String modelName,
                                          String systemPrompt, String userMessage,
                                          long timeoutMs) {
        int maxTokens = 2048;
        LLMCallConfig config = new LLMCallConfig(
                systemPrompt, maxTokens, Duration.ofMillis(Math.max(1L, timeoutMs)), 0, 0.2, false);
        return llmGateway.call(
                () -> planningClient.complete(modelName, systemPrompt, userMessage, maxTokens),
                modelName, config);
    }

    static ModelTier selectIntentTier(String question, int proMinChars) {
        int safeThreshold = Math.max(1, proMinChars);
        return codePointLength(question) >= safeThreshold ? ModelTier.HEAVY : ModelTier.LIGHT;
    }

    private static int codePointLength(String value) {
        if (value == null || value.isBlank()) return 0;
        return value.codePointCount(0, value.length());
    }

    public record IntentModelResponse(String content, String modelName, String modelTier,
                                      int questionChars, long elapsedMs) {
    }

    /**
     * 调用默认 DeepSeek Chat 模型。
     * <p>
     * 用于非意图分析场景，保留兼容入口。
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
                "deepseek-v4-flash",
                config);

        if (result.success()) {
            log.info("[ModelRouting] 推理完成: {} chars, {}ms",
                    result.content() != null ? result.content().length() : 0, result.elapsedMs());
            return result.content();
        }

        log.error("[ModelRouting] 推理失败: {}", result.errorMessage());
        throw new RuntimeException("DeepSeek model API call failed: " + result.errorMessage());
    }

    /**
     * 获取底层 ChatClient（供需要精细控制 prompt 的场景使用）
     */
    public ChatClient getChatClient() {
        return chatClient;
    }
}
