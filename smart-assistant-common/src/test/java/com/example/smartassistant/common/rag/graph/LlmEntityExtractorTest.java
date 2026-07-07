/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.graph;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmEntityExtractorTest {

    @Mock private ChatModel chatModel;
    @Mock private AiChatService aiChatService;

    @Test
    @DisplayName("chatModel 为 null 时不可用且抽取返回空")
    void testUnavailableWhenNoModel() {
        LlmEntityExtractor extractor = new LlmEntityExtractor(null);
        assertFalse(extractor.isAvailable());
        assertTrue(extractor.extract("任意文本", "doc-1", "kb1").isEmpty());
    }

    @Test
    @DisplayName("LLM 返回结构化结果时正确映射为实体与关系，且实体 ID 按名称稳定生成")
    void testMappingFromLlmSchema() {
        LlmEntityExtractor.ExtractionSchema schema = new LlmEntityExtractor.ExtractionSchema(
                List.of(
                        new LlmEntityExtractor.EntityDto("智能手表", "product", "可穿戴设备"),
                        new LlmEntityExtractor.EntityDto("张三", "user", "下单用户")),
                List.of(
                        new LlmEntityExtractor.RelationDto("张三", "智能手表", "下单", "用户购买", 0.9)));

        when(aiChatService.entity(any(ChatModel.class), anyString(), anyString(),
                eq(LlmEntityExtractor.ExtractionSchema.class))).thenReturn(schema);

        LlmEntityExtractor extractor = new LlmEntityExtractor(chatModel, aiChatService);
        assertTrue(extractor.isAvailable());

        EntityExtractor.ExtractionResult result = extractor.extract(
                "张三下单购买了一块智能手表，用于日常健康监测。", "doc-42", "kb_tenant");

        assertEquals(2, result.entities().size());
        assertEquals(1, result.relations().size());

        EntityNode watch = result.entities().stream()
                .filter(n -> "智能手表".equals(n.getName())).findFirst().orElseThrow();
        assertEquals("product", watch.getType());
        assertEquals("doc-42", watch.getSourceDocId());
        // 实体 ID 由名称归一化哈希稳定生成
        assertTrue(watch.getId().startsWith("ent:"));

        EntityRelation rel = result.relations().get(0);
        assertEquals("张三", rel.getSourceId());
        assertEquals("智能手表", rel.getTargetId());
        assertEquals("下单", rel.getRelationType());
        assertEquals(0.9, rel.getConfidence(), 1e-9);
        assertTrue(rel.getId().startsWith("rel:"));
    }

    @Test
    @DisplayName("LLM 抽取异常时降级返回空结果，不抛出")
    void testFallbackOnException() {
        when(aiChatService.entity(any(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("llm down"));

        LlmEntityExtractor extractor = new LlmEntityExtractor(chatModel, aiChatService);
        assertDoesNotThrow(() -> {
            EntityExtractor.ExtractionResult r = extractor.extract("文本", "d", "kb");
            assertTrue(r.isEmpty());
        });
    }

    @Test
    @DisplayName("无 advisor 链（aiChatService=null）时仍可用并抽取")
    void testBareChatClientPath() {
        // 无 aiChatService，走裸 ChatClient 退化路径（此处仅验证可用性判定与异常隔离）
        LlmEntityExtractor extractor = new LlmEntityExtractor(chatModel);
        assertTrue(extractor.isAvailable());
        // chatModel 为 mock，裸 ChatClient.create(mock) 不会真实调用，抽取返回 EMPTY 但不抛
        assertDoesNotThrow(() -> extractor.extract("文本", "d", "kb"));
    }
}
