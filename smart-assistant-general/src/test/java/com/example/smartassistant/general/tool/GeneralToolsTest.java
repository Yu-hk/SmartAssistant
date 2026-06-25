/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.tool;

import com.example.smartassistant.general.sandbox.ScriptSandbox;
import com.example.smartassistant.general.sandbox.ScriptSandboxProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GeneralTools 单元测试
 * 验证数学计算、单位转换、HTML 清理等功能
 */
class GeneralToolsTest {

    private final GeneralTools tools =
            new GeneralTools(null, new ScriptSandbox(new ScriptSandboxProperties()));

    // ========== 数学计算 ==========

    @Test
    void simpleAddition() {
        assertEquals("5", tools.calculate("2 + 3"));
    }

    @Test
    void simpleSubtraction() {
        assertEquals("3", tools.calculate("10 - 7"));
    }

    @Test
    void simpleMultiplication() {
        assertEquals("12", tools.calculate("3 * 4"));
    }

    @Test
    void simpleDivision() {
        assertEquals("3", tools.calculate("9 / 3"));
    }

    @Test
    void complexExpression() {
        assertEquals("35", tools.calculate("(12 + 8) * 3.5 / 2"));
    }

    @Test
    void exponentiation() {
        assertEquals("9", tools.calculate("3 ^ 2"));
    }

    @Test
    void squareRoot() {
        assertEquals("12", tools.calculate("sqrt(144)"));
    }

    @Test
    void negativeNumber() {
        assertEquals("-5", tools.calculate("-5"));
    }

    @Test
    void piConstant() {
        assertTrue(tools.calculate("pi").startsWith("3.14159"));
    }

    @Test
    void invalidExpression() {
        assertTrue(tools.calculate("abc").contains("error_code"));
    }

    @Test
    void divisionByZero() {
        assertNotNull(tools.calculate("1/0")); // 不应抛出异常
    }

    // ========== 温度转换 ==========

    @Test
    void celsiusToFahrenheit() {
        assertEquals("32°F", tools.convertTemperature(0, TemperatureUnit.C, TemperatureUnit.F));
        assertEquals("212°F", tools.convertTemperature(100, TemperatureUnit.C, TemperatureUnit.F));
    }

    @Test
    void fahrenheitToCelsius() {
        assertEquals("0°C", tools.convertTemperature(32, TemperatureUnit.F, TemperatureUnit.C));
        assertEquals("100°C", tools.convertTemperature(212, TemperatureUnit.F, TemperatureUnit.C));
    }

    @Test
    void celsiusToKelvin() {
        assertEquals("273.15K", tools.convertTemperature(0, TemperatureUnit.C, TemperatureUnit.K));
        assertEquals("373.15K", tools.convertTemperature(100, TemperatureUnit.C, TemperatureUnit.K));
    }

    @Test
    void kelvinToCelsius() {
        assertEquals("0°C", tools.convertTemperature(273.15, TemperatureUnit.K, TemperatureUnit.C));
    }

    @Test
    void sameUnit() {
        assertEquals("25°C", tools.convertTemperature(25, TemperatureUnit.C, TemperatureUnit.C));
    }

    @Test
    void invalidTemperatureUnit() {
        assertTrue(tools.convertTemperature(100, TemperatureUnit.fromString("X"), TemperatureUnit.C).contains("错误")
                || tools.convertTemperature(100, TemperatureUnit.fromString("X"), TemperatureUnit.C).contains("失败"));
    }

    // ========== 长度转换 ==========

    @Test
    void metersToKilometers() {
        assertEquals("1 km", tools.convertLength(1000, LengthUnit.M, LengthUnit.KM));
    }

    @Test
    void kilometersToMeters() {
        assertEquals("1000 m", tools.convertLength(1, LengthUnit.KM, LengthUnit.M));
    }

    @Test
    void metersToFeet() {
        String result = tools.convertLength(1, LengthUnit.M, LengthUnit.FT);
        assertTrue(result.startsWith("3.28") || result.startsWith("3.2808"));
    }

    @Test
    void inchesToCentimeters() {
        assertEquals("2.54 cm", tools.convertLength(1, LengthUnit.IN, LengthUnit.CM));
    }

    @Test
    void invalidLengthUnit() {
        assertTrue(tools.convertLength(100, LengthUnit.fromString("xyz"), LengthUnit.M).contains("失败"));
    }

    // ========== 重量转换 ==========

    @Test
    void kilogramsToGrams() {
        assertEquals("1000 g", tools.convertWeight(1, WeightUnit.KG, WeightUnit.G));
    }

    @Test
    void gramsToKilograms() {
        assertEquals("1 kg", tools.convertWeight(1000, WeightUnit.G, WeightUnit.KG));
    }

    @Test
    void poundsToKilograms() {
        String result = tools.convertWeight(1, WeightUnit.LB, WeightUnit.KG);
        assertTrue(result.startsWith("0.453"));
    }

    @Test
    void tonsToKilograms() {
        assertEquals("1000 kg", tools.convertWeight(1, WeightUnit.T, WeightUnit.KG));
    }

    @Test
    void invalidWeightUnit() {
        assertTrue(tools.convertWeight(100, WeightUnit.fromString("xyz"), WeightUnit.KG).contains("失败"));
    }

    // ========== 边界条件 ==========

    @Test
    void zeroInput() {
        assertEquals("0", tools.calculate("0"));
        assertEquals("0°C", tools.convertTemperature(0, TemperatureUnit.C, TemperatureUnit.C));
        assertEquals("0 m", tools.convertLength(0, LengthUnit.M, LengthUnit.M));
        assertEquals("0 kg", tools.convertWeight(0, WeightUnit.KG, WeightUnit.KG));
    }

    @Test
    void negativeTemperature() {
        assertEquals("-40°F", tools.convertTemperature(-40, TemperatureUnit.C, TemperatureUnit.F)); // -40°C = -40°F
    }

    @Test
    void largeNumbers() {
        assertEquals("1000000", tools.calculate("1000000"));
        assertTrue(tools.calculate("999999 * 999999").length() > 10);
    }
}
