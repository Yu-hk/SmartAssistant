package com.example.smartassistant.common.gateway.llm;

import java.time.Duration;

/**
 * LLM 调用配置（不可变）。
 * <p>
 * 用于 AgentLLMGateway 的统一调用，替代各模块各自创建 ChatClient 时的分散配置。
 * </p>
 *
 * @param systemPrompt  系统指令（可选）
 * @param maxTokens     最大生成 Token 数（默认 2048）
 * @param timeout       调用超时（默认 30s）
 * @param maxRetries    最大重试次数（默认 2，0=不重试）
 * @param temperature   温度参数（默认 0.5，null 使用模型默认值）
 * @param enableCircuitBreaker 是否启用熔断保护（默认 false，尝鲜阶段）
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
public record LLMCallConfig(
        String systemPrompt,
        int maxTokens,
        Duration timeout,
        int maxRetries,
        Double temperature,
        boolean enableCircuitBreaker
) {
    public static final int DEFAULT_MAX_TOKENS = 2048;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final double DEFAULT_TEMPERATURE = 0.5;

    public LLMCallConfig {
        if (maxTokens <= 0) maxTokens = DEFAULT_MAX_TOKENS;
        if (timeout == null || timeout.isNegative()) timeout = DEFAULT_TIMEOUT;
        if (maxRetries < 0) maxRetries = DEFAULT_MAX_RETRIES;
    }

    /** 快速创建：纯用户消息无 system prompt 的配置 */
    public static LLMCallConfig simple() {
        return new LLMCallConfig(null, DEFAULT_MAX_TOKENS, DEFAULT_TIMEOUT, 0, DEFAULT_TEMPERATURE, false);
    }

    /** 快速创建：轻量模型常用配置（低温度 + 短超时） */
    public static LLMCallConfig light() {
        return new LLMCallConfig(null, 1024, Duration.ofSeconds(15), 1, 0.1, false);
    }

    /** 快速创建：Agent 推理常用配置 */
    public static LLMCallConfig agent() {
        return new LLMCallConfig(null, 4096, Duration.ofSeconds(60), 2, 0.5, true);
    }
}
