/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PgVectorKnowledgeBase 缺陷修复验证（缺陷#1 维度动态化 / 缺陷#2 真实余弦距离）。
 * <p>
 * 这两个缺陷在 PG 不可用时无法跑集成测试，但可通过单测直接证明修复逻辑：
 * <ul>
 *   <li>缺陷#1：建表维度来自 {@code BgeEmbeddingModel.dimensions()}（如 1024），而非写死 384；
 *       本测试用非 384 维度（1536）构造，断言 DDL 中出现对应维度且不含 384。</li>
 *   <li>缺陷#2：相似度 = 1 - 余弦距离，由静态方法 {@code realCosineScore(dist)} 表达。</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class PgVectorKnowledgeBaseDefectTest {

    @Mock
    private BgeEmbeddingModel bge;

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private EmbeddingModel remoteEmbedding;

    @Test
    void dimensionIsDynamicFromBgeNotHardcoded384() {
        // 使用非 384 的维度，证明建表维度来自 BgeEmbeddingModel.dimensions()，而非写死 384
        when(bge.dimensions()).thenReturn(1536);

        new PgVectorKnowledgeBase("kb", bge, jdbc, null, null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).execute(captor.capture());

        String ddl = captor.getAllValues().stream()
                .filter(s -> s.contains("CREATE TABLE IF NOT EXISTS knowledge_docs"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未捕获到 knowledge_docs 建表 DDL"));

        assertTrue(ddl.contains("embedding vector(1536)"),
                "建表维度应来自 BgeEmbeddingModel.dimensions()(1536)，而非硬编码 384");
        assertFalse(ddl.contains("vector(384)"), "不应写死 384 维");
    }

    @Test
    void cosineScoreIsOneMinusDistance() {
        // 缺陷#2 修复：相似度 = 1 - 余弦距离（pgvector <-> 返回余弦距离，范围 [0,2]）
        assertEquals(1.0, PgVectorKnowledgeBase.realCosineScore(0.0), 1e-9);
        assertEquals(0.5, PgVectorKnowledgeBase.realCosineScore(0.5), 1e-9);
        assertEquals(0.0, PgVectorKnowledgeBase.realCosineScore(1.0), 1e-9);
        assertEquals(-1.0, PgVectorKnowledgeBase.realCosineScore(2.0), 1e-9);
    }

    @Test
    void insertPersistsKnowledgeBaseAndKeepsPlaceholderCountAligned() {
        when(remoteEmbedding.dimensions()).thenReturn(1024);
        when(remoteEmbedding.embed(anyString())).thenReturn(new float[1024]);
        RecordingJdbcTemplate recording = new RecordingJdbcTemplate();
        PgVectorKnowledgeBase kb = new PgVectorKnowledgeBase(
                "order_knowledge", remoteEmbedding, recording, null, null);

        kb.addDocument(new KnowledgeDocument(
                "doc-1", "退款政策", "退款将在审核后原路返回",
                "售后", "退款", -1, -1));

        assertTrue(recording.lastUpdateSql.contains("knowledge_base"));
        assertEquals("order_knowledge", recording.lastArgs[1]);
        long placeholders = recording.lastUpdateSql.chars().filter(ch -> ch == '?').count();
        assertEquals(placeholders, recording.lastArgs.length,
                "SQL placeholder 数量必须与 JdbcTemplate 参数数量一致（包括 NULL embedding）");
        assertTrue(Arrays.asList(recording.lastArgs).contains("doc-1"));
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String lastUpdateSql;
        private Object[] lastArgs;

        @Override
        public void execute(String sql) {
            // Constructor schema initialization is intentionally ignored.
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            // No existing PostgreSQL column in this SQL-capture-only test.
            return null;
        }

        @Override
        public int update(String sql, Object... args) {
            this.lastUpdateSql = sql;
            this.lastArgs = args;
            return 1;
        }
    }
}
