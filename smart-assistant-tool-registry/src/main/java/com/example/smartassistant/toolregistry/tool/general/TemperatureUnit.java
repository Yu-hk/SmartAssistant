/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.toolregistry.tool.general;

/**
 * Temperature unit enum for conversion tools.
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
}
