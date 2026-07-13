/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.tool;

/**
 * Weight unit enum with built-in conversion factors relative to kilograms.
 */
public enum WeightUnit {
    KG("kg", 1.0),
    G("g", 0.001),
    MG("mg", 0.000_001),
    LB("lb", 0.45359237),
    OZ("oz", 0.028349523125),
    T("t", 1000.0);

    private final String symbol;
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

    public double toKg(double value) {
        return value * toKgFactor;
    }

    public double fromKg(double kg) {
        return kg / toKgFactor;
    }
}
