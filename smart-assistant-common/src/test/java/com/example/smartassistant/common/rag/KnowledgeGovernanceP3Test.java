/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.rag.document.DocumentParseRouter;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import com.example.smartassistant.common.rag.chunking.DocumentChunker;
import com.example.smartassistant.common.rag.ingestion.ChunkQualityScorer;
import com.example.smartassistant.common.rag.ingestion.ContentHashCache;
import com.example.smartassistant.common.rag.ingestion.IngestAuditRecorder;
import com.example.smartassistant.common.rag.ingestion.IngestionResult;
import com.example.smartassistant.common.rag.ingestion.KnowledgeIngestionService;
import com.example.smartassistant.common.rag.ingestion.LoggingIngestAuditRecorder;
import com.example.smartassistant.common.rag.ingestion.PiiScrubber;
import com.example.smartassistant.common.rag.ingestion.ReviewQueueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * P3 知识库版本治理（打标注入 + 版本历史 + 回滚 + 审批门禁）内存模式测试。
 * <p>无需 PG / 嵌入服务，全部基于 {@link InMemoryKnowledgeBase} 与桩路由。</p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeGovernanceP3Test {

    @Mock
    private DocumentParseRouter router;

    @Mock
    private DocumentChunker chunker;

    @Mock
    private BgeEmbeddingModel bge;

    private static final String LONG_CONTENT =
            "知识库版本治理测试内容应足够长以通过质量门禁测试与语义分块校验，"
                    + "验证摄入打标、版本历史、回滚与审批门禁等治理能力在内存模式下行为符合预期。";

    private KnowledgeIngestionService newService(KnowledgeBase kb) {
        KnowledgeIngestionService svc = new KnowledgeIngestionService(
                router, chunker, null, kb,
                new ContentHashCache(), new PiiScrubber(), new ChunkQualityScorer(),
                AuthorityLevel.L2_INTERNAL, new LoggingIngestAuditRecorder());
        svc.setChangeDetectionEnabled(false);
        return svc;
    }

    private void stubParseAndChunk(String docId) {
        ParsedDocument parsed = ParsedDocument.builder()
                .docId(docId).title("政策").content(LONG_CONTENT).build();
        when(router.parse(anyString())).thenReturn(List.of(parsed));
        when(chunker.chunk(any())).thenReturn(List.of(
                new KnowledgeDocument(docId, "政策", LONG_CONTENT, "cat", "kw", -1, -1)));
    }

    // ==================== P3-1 打标注入 ====================

    @Test
    void ingestTagsBatchIdChecksumAndVersionField() {
        InMemoryKnowledgeBase kb = new InMemoryKnowledgeBase("kb", bge, null, null);
        KnowledgeIngestionService svc = newService(kb);
        stubParseAndChunk("file-p1-s1");

        IngestionResult result = svc.parseAndIngest("dummy.txt", "tenantA", "v2");

        assertTrue(result.isSuccess());
        KnowledgeDocument doc = kb.getDocument("file-p1-s1-v2");
        assertNotNull(doc, "v2 应带 -v2 后缀入库");
        // ⭐ version 字段与 id 后缀一致（显式参数优先）
        assertEquals("v2", doc.getVersion(), "version 字段应等于显式传入的 v2");
        // ⭐ 摄入打标：批次 ID 与校验和非空
        assertFalse(doc.getIngestBatchId().isBlank(), "ingestBatchId 应被注入");
        assertFalse(doc.getRawChecksum().isBlank(), "rawChecksum 应被注入");
        // 同一批次文档（本例单块）共享批次 ID
        assertEquals(doc.getIngestBatchId(), result.ingestBatchId());
    }

    // ==================== P3-2 版本历史 API ====================

    @Test
    void listVersionsByBaseDocIdReturnsAllVersions() {
        InMemoryKnowledgeBase kb = new InMemoryKnowledgeBase("kb", bge, null, null);
        kb.addDocument(new KnowledgeDocument("d-v1", "t", "c", "cat", "kw", -1, -1,
                "", "v1", "", 0, "", AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE,
                "v1", ChunkRole.STANDALONE, "PDF", "cs1", "b1"));
        kb.addDocument(new KnowledgeDocument("d-v2", "t", "c", "cat", "kw", -1, -1,
                "", "v2", "", 0, "", AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE,
                "v1", ChunkRole.STANDALONE, "PDF", "cs2", "b1"));

        List<DocumentVersionMeta> metas = kb.listVersionsByBaseDocId("d");

        assertEquals(2, metas.size(), "应返回同一基础文档的两个版本");
        assertTrue(metas.stream().allMatch(m -> "b1".equals(m.ingestBatchId())),
                "版本元信息应携带摄入批次 ID");
        assertTrue(metas.stream().anyMatch(m -> "v1".equals(m.version())));
        assertTrue(metas.stream().anyMatch(m -> "v2".equals(m.version())));
    }

    // ==================== P3-3 回滚 + 批次删除 ====================

    @Test
    void rollbackToVersionActivatesTargetAndSupersedesOthers() {
        InMemoryKnowledgeBase kb = new InMemoryKnowledgeBase("kb", bge, null, null);
        kb.addDocument(new KnowledgeDocument("d-v1", "t", "c", "cat", "kw", -1, -1,
                "", "v1", "", 0, "", AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE,
                "v1", ChunkRole.STANDALONE, "PDF", "cs1", "b1"));
        kb.addDocument(new KnowledgeDocument("d-v2", "t", "c", "cat", "kw", -1, -1,
                "", "v2", "", 0, "", AuthorityLevel.L2_INTERNAL, DocumentStatus.SUPERSEDED,
                "v1", ChunkRole.STANDALONE, "PDF", "cs2", "b1"));

        kb.rollbackToVersion("d", "v1");

        assertEquals(DocumentStatus.ACTIVE, kb.getDocument("d-v1").getDocumentStatus(),
                "回滚目标版本应置 ACTIVE");
        assertEquals(DocumentStatus.SUPERSEDED, kb.getDocument("d-v2").getDocumentStatus(),
                "其余版本应置 SUPERSEDED");
    }

    @Test
    void removeByIngestBatchIdDeletesWholeBatch() {
        InMemoryKnowledgeBase kb = new InMemoryKnowledgeBase("kb", bge, null, null);
        kb.addDocument(new KnowledgeDocument("d-v1", "t", "c", "cat", "kw", -1, -1,
                "", "v1", "", 0, "", AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE,
                "v1", ChunkRole.STANDALONE, "PDF", "cs1", "b1"));
        kb.addDocument(new KnowledgeDocument("d-v2", "t", "c", "cat", "kw", -1, -1,
                "", "v2", "", 0, "", AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE,
                "v1", ChunkRole.STANDALONE, "PDF", "cs2", "b1"));

        kb.removeByIngestBatchId("b1");

        assertEquals(0, kb.size(), "按批次删除应销毁该批次全部 chunk");
    }

    // ==================== P3-4 审批门禁（Q7 核心）====================

    @Test
    void newSourceIsHeldForApprovalThenActivated() {
        InMemoryKnowledgeBase kb = new InMemoryKnowledgeBase("kb", bge, null, null);
        ReviewQueueService rq = new ReviewQueueService();
        KnowledgeIngestionService svc = newService(kb);
        svc.setRequireApproval(true);
        svc.setReviewQueueService(rq);
        stubParseAndChunk("file-p1-s1");

        IngestionResult result = svc.parseAndIngest("dummy.txt", "tenantA", "v1");

        // ① 待审批：返回 held，且文档以 QUARANTINED 隔离（检索不可见）
        assertTrue(result.isHeld(), "新源首次入库应进入待审批挂起");
        assertFalse(result.ingestBatchId().isBlank(), "应返回批次 ID 供审批定位");
        assertEquals(1, rq.pendingCount(), "待审批次应进入复核队列");
        assertEquals(DocumentStatus.QUARANTINED,
                kb.getDocument("file-p1-s1-v1").getDocumentStatus(),
                "待审批文档应以 QUARANTINED 隔离，检索不可见");

        // ② 审批通过：激活该批次
        boolean ok = svc.approveBatch(result.ingestBatchId());
        assertTrue(ok);
        assertEquals(DocumentStatus.ACTIVE,
                kb.getDocument("file-p1-s1-v1").getDocumentStatus(),
                "审批通过后文档应转为 ACTIVE 可被检索");
    }

    @Test
    void rejectBatchDestroysQuarantinedDocs() {
        InMemoryKnowledgeBase kb = new InMemoryKnowledgeBase("kb", bge, null, null);
        ReviewQueueService rq = new ReviewQueueService();
        KnowledgeIngestionService svc = newService(kb);
        svc.setRequireApproval(true);
        svc.setReviewQueueService(rq);
        stubParseAndChunk("file-p1-s1");

        IngestionResult result = svc.parseAndIngest("dummy.txt", "tenantA", "v1");
        assertTrue(result.isHeld());

        boolean ok = svc.rejectBatch(result.ingestBatchId());
        assertTrue(ok);
        assertNull(kb.getDocument("file-p1-s1-v1"),
                "审批拒绝应物理销毁该批次文档");
        assertEquals(0, rq.pendingCount(), "拒绝后复核队列应清空该批次");
    }

    @Test
    void existingSourceSkipApprovalWhenActivePresent() {
        InMemoryKnowledgeBase kb = new InMemoryKnowledgeBase("kb", bge, null, null);
        // 预置已生效版本（模拟已审批通过的线上版本）
        kb.addDocument(new KnowledgeDocument("file-p1-s1", "旧版", "历史内容", "cat", "kw", -1, -1,
                "", "v1", "", 0, "", AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE,
                "v1", ChunkRole.STANDALONE, "PDF", "cs0", "b0"));
        KnowledgeIngestionService svc = newService(kb);
        svc.setRequireApproval(true);
        stubParseAndChunk("file-p1-s1");

        IngestionResult result = svc.parseAndIngest("dummy.txt", "tenantA", "v2");

        // 已存在 ACTIVE 版本 → 免审直接生效（非覆盖式标记旧版 SUPERSEDED）
        assertTrue(result.isSuccess(), "已有 ACTIVE 版本时覆盖式更新应免审直接生效");
        assertEquals(DocumentStatus.ACTIVE, kb.getDocument("file-p1-s1-v2").getDocumentStatus());
        assertEquals(DocumentStatus.SUPERSEDED, kb.getDocument("file-p1-s1").getDocumentStatus());
    }
}
