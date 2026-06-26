/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.output;

/**
 * 结构化输出 Schema 定义——描述 LLM 应该输出什么样的 JSON。
 * <p>
 * 包含目标 Java 类型和描述信息，用于 {@link StructuredOutputService} 自动生成
 * LLM 提示词中的 JSON Schema 约束，以及对输出结果的验证和重试。
 * </p>
 *
 * @param <T> 目标 Java 类型
 */
public class StructuredOutputSchema<T> {

    /** 目标类型（LLM 输出将被反序列化为该类型） */
    private final Class<T> targetType;

    /** JSON 示例（可选，用于生成更精确的提示词） */
    private final String jsonExample;

    /** 字段描述（每个字段的中文含义，用于 LLM 理解） */
    private final String fieldDescriptions;

    /** 额外约束说明 */
    private final String extraInstructions;

    private StructuredOutputSchema(Class<T> targetType, String jsonExample,
                                   String fieldDescriptions, String extraInstructions) {
        this.targetType = targetType;
        this.jsonExample = jsonExample;
        this.fieldDescriptions = fieldDescriptions;
        this.extraInstructions = extraInstructions;
    }

    public Class<T> getTargetType() { return targetType; }
    public String getJsonExample() { return jsonExample; }
    public String getFieldDescriptions() { return fieldDescriptions; }
    public String getExtraInstructions() { return extraInstructions; }

    // ------- Builder -------

    public static <T> Builder<T> builder(Class<T> targetType) {
        return new Builder<>(targetType);
    }

    public static class Builder<T> {
        private final Class<T> targetType;
        private String jsonExample;
        private String fieldDescriptions;
        private String extraInstructions;

        Builder(Class<T> targetType) { this.targetType = targetType; }

        public Builder<T> withJsonExample(String jsonExample) {
            this.jsonExample = jsonExample; return this;
        }

        public Builder<T> withFieldDescriptions(String fieldDescriptions) {
            this.fieldDescriptions = fieldDescriptions; return this;
        }

        public Builder<T> withExtraInstructions(String extraInstructions) {
            this.extraInstructions = extraInstructions; return this;
        }

        public StructuredOutputSchema<T> build() {
            return new StructuredOutputSchema<>(targetType, jsonExample,
                    fieldDescriptions, extraInstructions);
        }
    }
}
