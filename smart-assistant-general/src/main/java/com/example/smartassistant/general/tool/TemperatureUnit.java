/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.tool;

import java.util.stream.Stream;

/**
 * 温度单位枚举。
 * 用于 {@link GeneralTools#convertTemperature(double, TemperatureUnit, TemperatureUnit)} 的参数约束，
 * 自动生成 OpenAI function calling schema 中的 enum 校验。
 */
public enum TemperatureUnit {

    C("C"),
    F("F"),
    K("K");

    private final String symbol;

    TemperatureUnit(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    /**
     * 将字符串解析为对应的枚举值，支持别名匹配。
     *
     * @param text 用户或 LLM 传入的字符串
     * @return 匹配的枚举，匹配失败返回 null
     */
    public static TemperatureUnit fromString(String text) {
        if (text == null) return null;
        String normalized = text.trim().toUpperCase()
                .replace("°", "")
                .replace("℃", "C")
                .replace("℉", "F")
                .replace("CELSIUS", "C")
                .replace("FAHRENHEIT", "F")
                .replace("KELVIN", "K");
        return Stream.of(values())
                .filter(u -> u.symbol.equals(normalized) || u.name().equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
