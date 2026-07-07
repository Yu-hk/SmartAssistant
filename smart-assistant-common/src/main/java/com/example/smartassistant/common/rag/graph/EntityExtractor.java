/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.graph;

import java.util.List;

/**
 * 实体关系抽取器接口 — 从文档文本中提取实体和关系。
 * <p>
 * 设计为可插拔策略模式：
 * <ul>
 *   <li>默认降级 {@link NoopEntityExtractor}（返回空）</li>
 *   <li>生产环境接入 LLM（通过 {@code AiChatService.entity()} 结构化输出）</li>
 * </ul>
 * </p>
 */
public interface EntityExtractor {

    /**
     * 从文档文本中抽取实体和关系。
     *
     * @param content     文档文本内容
     * @param docId       文档 ID（用于溯源）
     * @param kbName      知识库名称（用于溯源）
     * @return 抽取结果，包含实体列表与关系列表
     */
    ExtractionResult extract(String content, String docId, String kbName);

    /** 此抽取器是否可用 */
    default boolean isAvailable() { return true; }

    /**
     * 抽取结果。
     *
     * @param entities  抽取到的实体列表
     * @param relations 抽取到的关系列表
     */
    record ExtractionResult(List<EntityNode> entities, List<EntityRelation> relations) {

        /** 空结果 */
        public static final ExtractionResult EMPTY = new ExtractionResult(List.of(), List.of());

        /** 是否为空 */
        public boolean isEmpty() { return entities.isEmpty() && relations.isEmpty(); }
    }
}
