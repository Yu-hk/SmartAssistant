/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单位换算枚举的纯逻辑单元测试（长度 / 重量 / 温度）。
 * <p>验证换算因子与「米 ↔ 单位」「千克 ↔ 单位」的往返一致性。</p>
 */
@DisplayName("[general] 单位换算枚举测试")
class UnitConversionTest {

    private static final double DELTA = 1e-9;

    // ============ 长度 ============

    @Test
    @DisplayName("长度：千米 ↔ 米 往返一致")
    void length_kmRoundTrip() {
        assertEquals(1000.0, LengthUnit.KM.toMeters(1.0), DELTA);
        assertEquals(1.0, LengthUnit.KM.fromMeters(1000.0), DELTA);
    }

    @Test
    @DisplayName("长度：厘米换算正确")
    void length_cmToMeters() {
        assertEquals(1.0, LengthUnit.CM.toMeters(100.0), DELTA);
        assertEquals(100.0, LengthUnit.CM.fromMeters(1.0), DELTA);
    }

    @Test
    @DisplayName("长度：英里/英尺/英寸因子正确")
    void length_otherFactors() {
        assertEquals(1609.344, LengthUnit.MI.toMeters(1.0), DELTA);
        assertEquals(0.3048, LengthUnit.FT.toMeters(1.0), DELTA);
        assertEquals(0.0254, LengthUnit.IN.toMeters(1.0), DELTA);
    }

    // ============ 重量 ============

    @Test
    @DisplayName("重量：磅 ↔ 千克 因子正确")
    void weight_lbToKg() {
        assertEquals(0.45359237, WeightUnit.LB.toKg(1.0), DELTA);
        assertEquals(1.0, WeightUnit.LB.fromKg(0.45359237), DELTA);
    }

    @Test
    @DisplayName("重量：克/毫克/吨往返一致")
    void weight_roundTrip() {
        assertEquals(0.001, WeightUnit.G.toKg(1.0), DELTA);
        assertEquals(0.000_001, WeightUnit.MG.toKg(1.0), DELTA);
        assertEquals(1000.0, WeightUnit.T.toKg(1.0), DELTA);
        assertEquals(1.0, WeightUnit.KG.fromKg(1.0), DELTA);
    }

    @Test
    @DisplayName("重量：盎司因子正确")
    void weight_ozFactor() {
        assertEquals(0.028349523125, WeightUnit.OZ.toKg(1.0), DELTA);
    }

    // ============ 温度符号 ============

    @Test
    @DisplayName("温度：符号映射正确")
    void temperature_symbols() {
        assertEquals("C", TemperatureUnit.C.getSymbol());
        assertEquals("F", TemperatureUnit.F.getSymbol());
        assertEquals("K", TemperatureUnit.K.getSymbol());
    }
}
