/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.tool;

/**
 * Image size presets for text-to-image generation.
 */
public enum ImageSize {
    SQUARE("1024*1024"),
    LANDSCAPE("1024*576"),
    PORTRAIT("576*1024");

    private final String value;

    ImageSize(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
