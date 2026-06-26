/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.output;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.error.ErrorRecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化输出服务——通过 JSON Schema 约束 + 自动重试，确保 LLM 输出符合预期格式。
 * <p>
 * 替代手写 Prompt + 自行 JSON 解析的模式，提供统一的"定义 Schema → 调用 LLM →
 * 验证 → 重试"闭环。参考 AgentScope Java 内置的结构化输出设计。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * StructuredOutputSchema<TaskAnalysisResult> schema = StructuredOutputSchema
 *     .builder(TaskAnalysisResult.class)
 *     .withJsonExample("{\"intent_category\":\"ORDER\",...}")
 *     .withFieldDescriptions("intent_category: 意图分类...")
 *     .build();
 *
 * StructuredOutputService service = new StructuredOutputService(llmCallFn);
 * TaskAnalysisResult result = service.call(schema, userQuestion);
 * }</pre>
 *
 * @param <T> 输出类型
 */
public class StructuredOutputService<T> {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputService.class);

    private static final int MAX_RETRY = 2;

    private final Function<String, String> llmCallFn;
    private final ObjectMapper objectMapper;
    private final ErrorRecoveryService recoveryService;

    /**
     * @param llmCallFn LLM 调用函数：输入 prompt，输出响应文本
     */
    public StructuredOutputService(Function<String, String> llmCallFn) {
        this.llmCallFn = llmCallFn;
        this.objectMapper = new ObjectMapper();
        this.recoveryService = ErrorRecoveryService.DEFAULT;
    }

    /**
     * 调用 LLM 并获取结构化输出。
     *
     * @param schema    输出 Schema 定义
     * @param userInput 用户输入（问题/待分析文本）
     * @return 解析后的结构化对象，解析失败返回 null
     */
    public T call(StructuredOutputSchema<T> schema, String userInput) {
        return call(schema, userInput, null);
    }

    /**
     * 调用 LLM 并获取结构化输出（带额外的 system prompt 前缀）。
     *
     * @param schema       输出 Schema 定义
     * @param userInput    用户输入
     * @param extraPrompt  额外 system prompt 前缀（可为 null）
     * @return 解析后的结构化对象
     */
    public T call(StructuredOutputSchema<T> schema, String userInput, String extraPrompt) {
        String systemPrompt = buildSystemPrompt(schema, extraPrompt);

        int attempt = 0;
        String lastError = null;

        while (attempt <= MAX_RETRY) {
            attempt++;
            try {
                String prompt = attempt == 1
                        ? systemPrompt + "\n\n用户输入:\n" + userInput
                        : systemPrompt + "\n\n用户输入:\n" + userInput
                        + "\n\n⚠️ 上次输出格式不合法:\n" + lastError
                        + "\n请严格按照要求的 JSON 格式重新输出。";

                String rawResponse = llmCallFn.apply(prompt);
                if (rawResponse == null || rawResponse.isBlank()) {
                    lastError = "LLM 返回空";
                    continue;
                }

                String json = extractJson(rawResponse);
                if (json == null) {
                    lastError = "未找到 JSON";
                    continue;
                }

                T result = objectMapper.readValue(json, schema.getTargetType());
                log.info("[StructuredOutput] ✅ 输出解析成功: type={}, attempt={}",
                        schema.getTargetType().getSimpleName(), attempt);
                return result;

            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("[StructuredOutput] 解析失败(第{}次): {}", attempt, e.getMessage());
                if (attempt > MAX_RETRY) break;
            }
        }

        log.error("[StructuredOutput] 输出解析失败(已重试{}次): type={}", MAX_RETRY,
                schema.getTargetType().getSimpleName());
        return null;
    }

    /**
     * 构建 system prompt：Schema 约束 + 示例 + 额外指令。
     */
    private String buildSystemPrompt(StructuredOutputSchema<T> schema, String extraPrompt) {
        StringBuilder sb = new StringBuilder();

        if (extraPrompt != null && !extraPrompt.isBlank()) {
            sb.append(extraPrompt).append("\n\n");
        }

        sb.append("你必须严格按照要求的 JSON 格式输出，只输出 JSON 对象，不包含任何其他文字。\n\n");

        if (schema.getFieldDescriptions() != null) {
            sb.append("字段说明：\n").append(schema.getFieldDescriptions()).append("\n\n");
        }

        if (schema.getJsonExample() != null) {
            sb.append("输出 JSON 格式示例：\n").append(schema.getJsonExample()).append("\n\n");
        }

        if (schema.getExtraInstructions() != null) {
            sb.append("额外要求：\n").append(schema.getExtraInstructions()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 从 LLM 响应中提取 JSON 字符串。
     */
    private String extractJson(String response) {
        if (response == null || response.isBlank()) return null;

        // 1. 尝试 ```json ... ``` 代码块
        Pattern codeBlock = Pattern.compile(
                "```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```", Pattern.CASE_INSENSITIVE);
        Matcher m = codeBlock.matcher(response);
        if (m.find()) {
            String c = m.group(1).trim();
            if (c.startsWith("{")) return c;
        }

        // 2. 裸 JSON { ... }
        int braceStart = response.indexOf("{");
        if (braceStart >= 0) {
            int depth = 0;
            for (int i = braceStart; i < response.length(); i++) {
                char c = response.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return response.substring(braceStart, i + 1);
                }
            }
        }
        return null;
    }
}
