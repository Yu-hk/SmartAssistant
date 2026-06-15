/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.embedding;

import java.util.List;

/**
 * 向量化响应，包含所有向量化结果。
 */
public class EmbeddingResponse {

    private final List<Embedding> results;

    public EmbeddingResponse(List<Embedding> results) {
        this.results = results;
    }

    public List<Embedding> getResults() {
        return results;
    }
}
