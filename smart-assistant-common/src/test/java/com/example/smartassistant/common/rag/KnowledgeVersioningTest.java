/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 治理能力单元测试——非覆盖式版本 + 隔离/回滚。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>{@code markSupersededByBaseId} 默认逻辑：保留 keepDocId(ACTIVE)，其余 SUPERSEDED，且从不物理删除</li>
 *   <li>{@code quarantine}/{@code restore} 隔离与回滚</li>
 *   <li>{@code PgVectorKnowledgeBase} 的 {@code updateStatus}/{@code listIdsByBaseDocId} 生成非破坏性 SQL</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeVersioningTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private BgeEmbeddingModel embeddingModel;

    // ==================== 接口默认治理逻辑（基于 RecordingKnowledgeBase） ====================

    @Test
    void nonCoverMarkSupersededKeepsNewAndSupersedesOld() {
        TestRecordingKnowledgeBase kb = new TestRecordingKnowledgeBase();
        kb.addDocument(doc("ORD-REFUND-001", "旧版退款规则：仅支持原路退回。"));
        kb.addDocument(doc("ORD-REFUND-001-v2", "新版退款规则：支持原路退回与余额退回。"));

        kb.markSupersededByBaseId("ORD-REFUND-001", "ORD-REFUND-001-v2");

        assertEquals(DocumentStatus.SUPERSEDED, kb.statusOf("ORD-REFUND-001"),
                "旧版本应被标记 SUPERSEDED（而非物理删除）");
        assertEquals(DocumentStatus.ACTIVE, kb.statusOf("ORD-REFUND-001-v2"),
                "保留的新版本应保持 ACTIVE");

        // 非覆盖：旧文档仍存在（未被物理删除）
        assertEquals(2, kb.listIdsByBaseDocId("ORD-REFUND-001").size(),
                "旧版本 id 仍应存在于库中，支持回滚/回溯");

        // 检索链路应排除 SUPERSEDED
        boolean oldExcluded = kb.search("退款", 5).stream()
                .noneMatch(h -> "ORD-REFUND-001".equals(h.getDocument().getId()));
        assertTrue(oldExcluded, "SUPERSEDED 文档不应出现在检索结果中");

        // 新版本应可检索
        boolean newReturned = kb.search("退款", 5).stream()
                .anyMatch(h -> "ORD-REFUND-001-v2".equals(h.getDocument().getId()));
        assertTrue(newReturned, "ACTIVE 新版本应出现在检索结果中");
    }

    @Test
    void markSupersededNeverPhysicallyDeletes() {
        TestRecordingKnowledgeBase kb = new TestRecordingKnowledgeBase();
        kb.addDocument(doc("ORD-REFUND-001", "旧版内容"));

        kb.markSupersededByBaseId("ORD-REFUND-001", "ORD-REFUND-001-v2");

        assertTrue(kb.removeByBaseCalls.isEmpty(),
                "非覆盖式标记旧版不应触发 removeByBaseDocId（物理删除）");
        assertTrue(kb.removeByIdCalls.isEmpty(),
                "非覆盖式标记旧版不应触发 removeDocument（物理删除）");
        assertTrue(kb.markSupersededBaseCalls.contains("ORD-REFUND-001"),
                "应触发 markSupersededByBaseId 治理调用");
    }

    @Test
    void quarantineThenRestore() {
        TestRecordingKnowledgeBase kb = new TestRecordingKnowledgeBase();
        kb.addDocument(doc("DOC-A", "可公开检索的内容"));

        kb.quarantine("DOC-A");
        assertEquals(DocumentStatus.QUARANTINED, kb.statusOf("DOC-A"));
        boolean quarantinedExcluded = kb.search("内容", 5).stream()
                .noneMatch(h -> "DOC-A".equals(h.getDocument().getId()));
        assertTrue(quarantinedExcluded, "被隔离文档应从检索中排除");

        kb.restore("DOC-A");
        assertEquals(DocumentStatus.ACTIVE, kb.statusOf("DOC-A"));
        boolean restoredReturned = kb.search("内容", 5).stream()
                .anyMatch(h -> "DOC-A".equals(h.getDocument().getId()));
        assertTrue(restoredReturned, "恢复后的文档应重新可检索");
    }

    @Test
    void updateStatusNullSafety() {
        TestRecordingKnowledgeBase kb = new TestRecordingKnowledgeBase();
        kb.addDocument(doc("DOC-B", "内容"));
        // 空 id / 空 status 应为 no-op，不抛异常
        kb.updateStatus(null, DocumentStatus.QUARANTINED);
        kb.updateStatus("", DocumentStatus.QUARANTINED);
        kb.updateStatus("DOC-B", null);
        assertEquals(DocumentStatus.ACTIVE, kb.statusOf("DOC-B"));
    }

    // ==================== PgVector 实现层：SQL 必须非破坏性 ====================

    @Test
    void pgVectorUpdateStatusIssuesNonDestructiveSql() {
        PgVectorKnowledgeBase kb = new PgVectorKnowledgeBase("pg", embeddingModel, jdbcTemplate, null);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        kb.updateStatus("doc-1", DocumentStatus.QUARANTINED);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());

        String upper = sql.getValue().toUpperCase();
        assertTrue(upper.contains("UPDATE KNOWLEDGE_DOCS SET DOCUMENT_STATUS"),
                "状态更新应使用 UPDATE，而非 DELETE");
        assertFalse(upper.contains("DELETE"),
                "非覆盖式治理：updateStatus 绝不应物理删除行");
        assertEquals("QUARANTINED", args.getValue()[0]);
        assertEquals("doc-1", args.getValue()[1]);
    }

    @Test
    void pgVectorListIdsMatchesBaseAndVersioned() {
        PgVectorKnowledgeBase kb = new PgVectorKnowledgeBase("pg", embeddingModel, jdbcTemplate, null);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(List.of("doc-1-v1", "doc-1-v2"));

        List<String> ids = kb.listIdsByBaseDocId("doc-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        // query(String, RowMapper, Object, Object)
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(), any());

        String upper = sql.getValue().toUpperCase();
        assertTrue(upper.contains("ID = ? OR ID LIKE ? || '-%'"),
                "应按基础ID精确匹配 + 版本化ID前缀匹配，以定位全部历史版本");
        assertEquals(List.of("doc-1-v1", "doc-1-v2"), ids);
    }

    @Test
    void pgVectorUpdateStatusNoopForBlankOrNull() {
        PgVectorKnowledgeBase kb = new PgVectorKnowledgeBase("pg", embeddingModel, jdbcTemplate, null);
        // 不应调用 jdbcTemplate.update
        kb.updateStatus(null, DocumentStatus.QUARANTINED);
        kb.updateStatus("", DocumentStatus.QUARANTINED);
        kb.updateStatus("x", null);
        verify(jdbcTemplate, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }

    // ==================== 辅助 ====================

    private static KnowledgeDocument doc(String id, String content) {
        return new KnowledgeDocument(id, "标题", content, "cat", "kw", -1, -1);
    }
}
