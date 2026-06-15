/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Getter;
import lombok.Setter;

/**
 * Token 消耗统计。
 * <p>对应 OpenAI Chat Completions / Responses API 的 {@code usage} 字段结构。
 * 作为 {@link AgentApiResponse#usage} 的类型，记录每次 API 调用的 token 消耗。</p>
 *
 * <pre>
 * {
 *   "promptTokens": 142,
 *   "completionTokens": 67,
 *   "totalTokens": 209,
 *   "modelName": "deepseek-chat",
 *   "cachedTokens": 50,
 *   "reasoningTokens": 20
 * }
 * </pre>
 */
@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"promptTokens", "completionTokens", "totalTokens", "modelName", "cachedTokens", "reasoningTokens"})
public class TokenUsage {
    /** 输入提示的 token 数（对应 OpenAI usage.prompt_tokens） */
    private int promptTokens;

    /** 模型生成的 token 数（对应 OpenAI usage.completion_tokens） */
    private int completionTokens;

    /** 总 token 数 = promptTokens + completionTokens（对应 OpenAI usage.total_tokens） */
    private int totalTokens;

    /** 模型名称（如 "deepseek-chat", "gpt-4o-mini"），用于精确计费 */
    private String modelName;

    /** 命中了上下文缓存的 token 数（可选，用于 DeepSeek/Claude 等支持缓存的服务） */
    private Integer cachedTokens;

    /** 推理模型的思考 token 数（可选，用于 o1/DeepSeek R1 等） */
    private Integer reasoningTokens;

    public TokenUsage() {}

    /** 标准三字段构造。 */
    public TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    /** 四字段构造（含模型名称）。 */
    public TokenUsage(int promptTokens, int completionTokens, int totalTokens, String modelName) {
        this(promptTokens, completionTokens, totalTokens);
        this.modelName = modelName;
    }

    /** 全字段构造。 */
    public TokenUsage(int promptTokens, int completionTokens, int totalTokens,
                      String modelName, Integer cachedTokens, Integer reasoningTokens) {
        this(promptTokens, completionTokens, totalTokens, modelName);
        this.cachedTokens = cachedTokens;
        this.reasoningTokens = reasoningTokens;
    }

    // ---- Getters / Setters ----

}
