/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.embedding;

import java.util.List;

/**
 * 向量化请求，封装需要向量化的文本列表。
 */
public class EmbeddingRequest {

    private final List<String> instructions;

    public EmbeddingRequest(List<String> instructions) {
        this.instructions = instructions;
    }

    public List<String> getInstructions() {
        return instructions;
    }
}
