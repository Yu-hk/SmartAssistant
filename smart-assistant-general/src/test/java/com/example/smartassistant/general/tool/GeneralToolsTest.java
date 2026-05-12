/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GeneralTools 单元测试
 * 验证数学计算、单位转换、HTML 清理等功能
 */
class GeneralToolsTest {

    private final GeneralTools tools = new GeneralTools(null);

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
        assertTrue(tools.calculate("abc").contains("无法计算"));
    }

    @Test
    void divisionByZero() {
        assertNotNull(tools.calculate("1/0")); // 不应抛出异常
    }

    // ========== 温度转换 ==========

    @Test
    void celsiusToFahrenheit() {
        assertEquals("32°F", tools.convertTemperature(0, "C", "F"));
        assertEquals("212°F", tools.convertTemperature(100, "C", "F"));
    }

    @Test
    void fahrenheitToCelsius() {
        assertEquals("0°C", tools.convertTemperature(32, "F", "C"));
        assertEquals("100°C", tools.convertTemperature(212, "F", "C"));
    }

    @Test
    void celsiusToKelvin() {
        assertEquals("273.15K", tools.convertTemperature(0, "C", "K"));
        assertEquals("373.15K", tools.convertTemperature(100, "C", "K"));
    }

    @Test
    void kelvinToCelsius() {
        assertEquals("0°C", tools.convertTemperature(273.15, "K", "C"));
    }

    @Test
    void sameUnit() {
        assertEquals("25°C", tools.convertTemperature(25, "C", "C"));
    }

    @Test
    void invalidTemperatureUnit() {
        assertTrue(tools.convertTemperature(100, "X", "C").contains("不支持"));
    }

    // ========== 长度转换 ==========

    @Test
    void metersToKilometers() {
        assertEquals("1 km", tools.convertLength(1000, "m", "km"));
    }

    @Test
    void kilometersToMeters() {
        assertEquals("1000 m", tools.convertLength(1, "km", "m"));
    }

    @Test
    void metersToFeet() {
        String result = tools.convertLength(1, "m", "ft");
        assertTrue(result.startsWith("3.28") || result.startsWith("3.2808"));
    }

    @Test
    void inchesToCentimeters() {
        assertEquals("2.54 cm", tools.convertLength(1, "in", "cm"));
    }

    @Test
    void invalidLengthUnit() {
        assertTrue(tools.convertLength(100, "xyz", "m").contains("不支持"));
    }

    // ========== 重量转换 ==========

    @Test
    void kilogramsToGrams() {
        assertEquals("1000 g", tools.convertWeight(1, "kg", "g"));
    }

    @Test
    void gramsToKilograms() {
        assertEquals("1 kg", tools.convertWeight(1000, "g", "kg"));
    }

    @Test
    void poundsToKilograms() {
        String result = tools.convertWeight(1, "lb", "kg");
        assertTrue(result.startsWith("0.453"));
    }

    @Test
    void tonsToKilograms() {
        assertEquals("1000 kg", tools.convertWeight(1, "t", "kg"));
    }

    @Test
    void invalidWeightUnit() {
        assertTrue(tools.convertWeight(100, "xyz", "kg").contains("不支持"));
    }

    // ========== 边界条件 ==========

    @Test
    void zeroInput() {
        assertEquals("0", tools.calculate("0"));
        assertEquals("0°C", tools.convertTemperature(0, "C", "C"));
        assertEquals("0 m", tools.convertLength(0, "m", "m"));
        assertEquals("0 kg", tools.convertWeight(0, "kg", "kg"));
    }

    @Test
    void negativeTemperature() {
        assertEquals("-40°F", tools.convertTemperature(-40, "C", "F")); // -40°C = -40°F
    }

    @Test
    void largeNumbers() {
        assertEquals("1000000", tools.calculate("1000000"));
        assertTrue(tools.calculate("999999 * 999999").length() > 10);
    }
}
