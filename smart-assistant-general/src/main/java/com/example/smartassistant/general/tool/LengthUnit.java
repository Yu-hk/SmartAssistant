/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.tool;

/**
 * Length unit enum with built-in conversion factors relative to meters.
 */
public enum LengthUnit {
    M("m", 1.0),
    KM("km", 1000.0),
    CM("cm", 0.01),
    MM("mm", 0.001),
    FT("ft", 0.3048),
    IN("in", 0.0254),
    MI("mi", 1609.344);

    private final String symbol;
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

    public double toMeters(double value) {
        return value * toMetersFactor;
    }

    public double fromMeters(double meters) {
        return meters / toMetersFactor;
    }
}
