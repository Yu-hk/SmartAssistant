/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.embedding;

import java.util.Arrays;

/**
 * 单个向量化结果，包含向量及其在请求中的索引。
 */
public class Embedding {

    private final float[] vector;
    private final int index;

    public Embedding(float[] vector, int index) {
        this.vector = vector;
        this.index = index;
    }

    public float[] getVector() {
        return vector;
    }

    public int getIndex() {
        return index;
    }

    @Override
    public String toString() {
        return "Embedding{index=" + index + ", vector=" + (vector != null ? vector.length + "d" : "null") + "}";
    }
}
