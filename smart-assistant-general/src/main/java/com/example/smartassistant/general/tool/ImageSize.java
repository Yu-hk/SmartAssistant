/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.tool;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 图片尺寸枚举。
 * 用于 {@link ImageTools#generateImage(String, ImageSize, Integer)} 的参数约束，
 * 自动生成 OpenAI function calling schema 中的 enum 校验。
 */
public enum ImageSize {

    /** 方形 1024x1024（默认） */
    SQUARE("1024*1024"),
    /** 横版 1024x576 */
    LANDSCAPE("1024*576"),
    /** 竖版 576x1024 */
    PORTRAIT("576*1024");

    private final String value;

    ImageSize(String value) {
        this.value = value;
    }

    /**
     * Jackson 序列化时使用的字符串值，Spring AI 据此生成 JSON Schema enum。
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 将字符串解析为对应的枚举值。
     *
     * @param text 用户或 LLM 传入的字符串
     * @return 匹配的枚举，匹配失败返回默认值 SQUARE
     */
    public static ImageSize fromString(String text) {
        if (text == null) return SQUARE;
        String normalized = text.trim().replace("×", "*").replace("x", "*").replace("X", "*");
        for (ImageSize size : values()) {
            if (size.value.equals(normalized) || size.name().equalsIgnoreCase(normalized)) {
                return size;
            }
        }
        return SQUARE; // 默认
    }
}
