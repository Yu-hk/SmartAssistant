/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.embedding;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * {@link EmbeddingController} 单元测试（mock {@link BgeEmbeddingModel}）。
 * <p>覆盖健康检查、维度、单条/批量嵌入、空输入与模型不可用等分支。</p>
 */
@DisplayName("[embedding] EmbeddingController 单元测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmbeddingControllerTest {

    @Mock
    private BgeEmbeddingModel embeddingModel;

    @InjectMocks
    private EmbeddingController controller;

    @BeforeEach
    void setUp() {
        when(embeddingModel.dimensions()).thenReturn(768);
    }

    // ============ 健康检查 ============

    @Test
    @DisplayName("health()：模型可用时返回 UP")
    void health_upWhenAvailable() {
        when(embeddingModel.isAvailable()).thenReturn(true);
        Map<String, Object> r = controller.health();
        assertEquals("UP", r.get("status"));
        assertEquals(768, r.get("dimensions"));
        assertEquals(Boolean.TRUE, r.get("available"));
    }

    @Test
    @DisplayName("health()：模型不可用时返回 DOWN")
    void health_downWhenUnavailable() {
        when(embeddingModel.isAvailable()).thenReturn(false);
        Map<String, Object> r = controller.health();
        assertEquals("DOWN", r.get("status"));
        assertEquals(Boolean.FALSE, r.get("available"));
    }

    @Test
    @DisplayName("dimensions()：返回模型维度")
    void dimensions_returnsModelDim() {
        Map<String, Object> r = controller.dimensions();
        assertEquals(768, r.get("dimensions"));
    }

    // ============ 单条嵌入 ============

    @Test
    @DisplayName("embed()：空白文本返回 error")
    void embed_blankText_returnsError() {
        Map<String, Object> r = controller.embed(Map.of("text", "   "));
        assertEquals("text 不能为空", r.get("error"));
    }

    @Test
    @DisplayName("embed()：有效文本返回向量与维度")
    void embed_validText_returnsEmbedding() {
        when(embeddingModel.isAvailable()).thenReturn(true);
        when(embeddingModel.embedding("成都美食")).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        Map<String, Object> r = controller.embed(Map.of("text", "成都美食"));
        @SuppressWarnings("unchecked")
        List<Float> emb = (List<Float>) r.get("embedding");
        assertNotNull(emb);
        assertEquals(3, emb.size());
        assertEquals(3, r.get("dimensions"));
        assertTrue(r.containsKey("duration_ms"));
    }

    @Test
    @DisplayName("embed()：模型返回 null（不可用）返回 error")
    void embed_modelReturnsNull_returnsError() {
        when(embeddingModel.isAvailable()).thenReturn(true);
        when(embeddingModel.embedding("x")).thenReturn(null);

        Map<String, Object> r = controller.embed(Map.of("text", "x"));
        assertEquals("嵌入失败，模型不可用", r.get("error"));
    }

    // ============ 批量嵌入 ============

    @Test
    @DisplayName("embedBatch()：空列表返回 error")
    void embedBatch_empty_returnsError() {
        Map<String, Object> r = controller.embedBatch(Map.of("texts", List.of()));
        assertEquals("texts 不能为空", r.get("error"));
    }

    @Test
    @DisplayName("embedBatch()：有效文本返回向量列表与计数")
    void embedBatch_valid_returnsEmbeddings() {
        when(embeddingModel.isAvailable()).thenReturn(true);
        when(embeddingModel.embedding("a")).thenReturn(new float[]{0.1f});
        when(embeddingModel.embedding("b")).thenReturn(null); // 单条失败 → 空列表占位

        Map<String, Object> r = controller.embedBatch(Map.of("texts", List.of("a", "b")));
        @SuppressWarnings("unchecked")
        List<List<Float>> embs = (List<List<Float>>) r.get("embeddings");
        assertNotNull(embs);
        assertEquals(2, embs.size());
        assertEquals(1, embs.get(0).size());
        assertTrue(embs.get(1).isEmpty());
        assertEquals(2, r.get("count"));
        assertEquals(768, r.get("dimensions"));
    }
}
