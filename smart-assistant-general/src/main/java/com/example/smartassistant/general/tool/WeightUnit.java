/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.tool;

import java.util.stream.Stream;

/**
 * 重量单位枚举，内置与千克(KG)的换算系数。
 * 用于 {@link GeneralTools#convertWeight(double, WeightUnit, WeightUnit)} 的参数约束。
 */
public enum WeightUnit {

    /** 千克 */
    KG("kg", 1.0),
    /** 克 */
    G("g", 0.001),
    /** 毫克 */
    MG("mg", 0.000_001),
    /** 磅 */
    LB("lb", 0.45359237),
    /** 盎司 */
    OZ("oz", 0.028349523125),
    /** 吨 */
    T("t", 1000.0);

    private final String symbol;
    /** 相对于千克的转换系数：1 unit = x 千克 */
    private final double toKgFactor;

    WeightUnit(String symbol, double toKgFactor) {
        this.symbol = symbol;
        this.toKgFactor = toKgFactor;
    }

    public String getSymbol() {
        return symbol;
    }

    public double getToKgFactor() {
        return toKgFactor;
    }

    /** 将重量值转换为千克 */
    public double toKg(double value) {
        return value * toKgFactor;
    }

    /** 从千克转换为当前单位 */
    public double fromKg(double kg) {
        return kg / toKgFactor;
    }

    /**
     * 将字符串解析为对应的枚举值。
     *
     * @param text 用户或 LLM 传入的字符串
     * @return 匹配的枚举，匹配失败返回 null
     */
    public static WeightUnit fromString(String text) {
        if (text == null) return null;
        String normalized = text.trim().toLowerCase();
        // 中文别名
        if ("千克".equals(normalized)) return KG;
        if ("克".equals(normalized)) return G;
        if ("毫克".equals(normalized)) return MG;
        if ("磅".equals(normalized)) return LB;
        if ("盎司".equals(normalized)) return OZ;
        if ("吨".equals(normalized)) return T;
        // 符号匹配
        String finalNormalized = normalized;
        return Stream.of(values())
                .filter(u -> u.symbol.equals(finalNormalized) || u.name().equalsIgnoreCase(finalNormalized))
                .findFirst()
                .orElse(null);
    }
}
