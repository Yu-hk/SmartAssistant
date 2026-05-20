/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.tool;

import java.util.stream.Stream;

/**
 * 长度单位枚举，内置与米(M)的换算系数。
 * 用于 {@link GeneralTools#convertLength(double, LengthUnit, LengthUnit)} 的参数约束。
 */
public enum LengthUnit {

    /** 米 */
    M("m", 1.0),
    /** 千米 */
    KM("km", 1000.0),
    /** 厘米 */
    CM("cm", 0.01),
    /** 毫米 */
    MM("mm", 0.001),
    /** 英尺 */
    FT("ft", 0.3048),
    /** 英寸 */
    IN("in", 0.0254),
    /** 英里 */
    MI("mi", 1609.344);

    private final String symbol;
    /** 相对于米的转换系数：1 unit = x 米 */
    private final double toMetersFactor;

    LengthUnit(String symbol, double toMetersFactor) {
        this.symbol = symbol;
        this.toMetersFactor = toMetersFactor;
    }

    public String getSymbol() {
        return symbol;
    }

    public double getToMetersFactor() {
        return toMetersFactor;
    }

    /** 将长度值转换为米 */
    public double toMeters(double value) {
        return value * toMetersFactor;
    }

    /** 从米转换为当前单位 */
    public double fromMeters(double meters) {
        return meters / toMetersFactor;
    }

    /**
     * 将字符串解析为对应的枚举值。
     *
     * @param text 用户或 LLM 传入的字符串
     * @return 匹配的枚举，匹配失败返回 null
     */
    public static LengthUnit fromString(String text) {
        if (text == null) return null;
        String normalized = text.trim().toLowerCase();
        // 中文别名
        if ("英尺".equals(normalized)) return FT;
        if ("英寸".equals(normalized)) return IN;
        if ("英里".equals(normalized)) return MI;
        // 符号匹配
        String finalNormalized = normalized;
        return Stream.of(values())
                .filter(u -> u.symbol.equals(finalNormalized) || u.name().equalsIgnoreCase(finalNormalized))
                .findFirst()
                .orElse(null);
    }
}
