/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.graph;

/**
 * 空操作实体关系抽取器 — 当无可用的 LLM 抽取引擎时的默认降级。
 * <p>
 * 始终返回空抽取结果。接入 LLM 抽取后替换为真实 {@link EntityExtractor} 实现。
 * </p>
 */
public class NoopEntityExtractor implements EntityExtractor {

    @Override
    public ExtractionResult extract(String content, String docId, String kbName) {
        return ExtractionResult.EMPTY;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
