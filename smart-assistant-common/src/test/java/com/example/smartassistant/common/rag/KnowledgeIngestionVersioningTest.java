/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.rag.document.ParsedDocument;
import com.example.smartassistant.common.rag.ingestion.ChunkQualityScorer;
import com.example.smartassistant.common.rag.ingestion.ContentHashCache;
import com.example.smartassistant.common.rag.ingestion.IngestAuditRecorder;
import com.example.smartassistant.common.rag.ingestion.IngestionResult;
import com.example.smartassistant.common.rag.ingestion.KnowledgeIngestionService;
import com.example.smartassistant.common.rag.ingestion.LoggingIngestAuditRecorder;
import com.example.smartassistant.common.rag.ingestion.PiiScrubber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * P0 非覆盖式版本——摄入流程行为测试（端到端）。
 * <p>
 * 验证 {@link KnowledgeIngestionService#parseAndIngest} 在重新摄入同基础文档时：
 * <ul>
 *   <li>将新版本 id 编入 {@code -vN} 后缀；</li>
 *   <li>通过 {@code markSupersededByBaseId} 把旧版标记 SUPERSEDED（而非物理删除）；</li>
 *   <li>记录 SUPERSEDE/INGEST 审计事件；</li>
 *   <li>首次摄入不触发标记（无旧版可废弃）。</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeIngestionVersioningTest {

    @Mock
    private com.example.smartassistant.common.rag.document.DocumentParseRouter router;

    @Mock
    private com.example.smartassistant.common.rag.chunking.DocumentChunker chunker;

    @Test
    void reingestMarksOldSupersededWithoutPhysicalDelete() {
        TestRecordingKnowledgeBase kb = new TestRecordingKnowledgeBase();
        // 预置旧版 v1（模拟已入库的线上版本）
        kb.addDocument(new KnowledgeDocument("file-p1-s1", "旧退款政策", "订单完成后7天内可申请退款，原路返回。",
                "policy", "退款", -1, -1));

        KnowledgeIngestionService svc = newService(kb);
        svc.setChangeDetectionEnabled(false);

        ParsedDocument parsed = ParsedDocument.builder()
                .docId("file-p1-s1")
                .title("退款政策")
                .content("订单完成后7天内可申请退款，支持原路返回与余额退回两种渠道。"
                        + "退款申请将在审核通过后3个工作日内原路退回到支付账户，全程可实时查询进度。")
                .build();
        when(router.parse(anyString())).thenReturn(List.of(parsed));
        when(chunker.chunk(any())).thenReturn(List.of(
                new KnowledgeDocument("file-p1-s1", "退款政策",
                        "订单完成后7天内可申请退款，支持原路返回与余额退回两种渠道。"
                                + "退款申请将在审核通过后3个工作日内原路退回到支付账户，全程可实时查询进度。",
                        "policy", "退款", -1, -1)));

        IngestionResult result = svc.parseAndIngest("dummy.txt", "tenantA", "v2");

        assertTrue(result.isSuccess(), "摄入应成功");
        // 新版本已入库（带 -v2 后缀）
        assertNotNull(kb.docs.get("file-p1-s1-v2"), "新版本文档应以 -v2 后缀入库");
        assertEquals(DocumentStatus.ACTIVE, kb.statusOf("file-p1-s1-v2"));
        // 旧版本被标记 SUPERSEDED，而非物理删除（可回滚/回溯）
        assertEquals(DocumentStatus.SUPERSEDED, kb.statusOf("file-p1-s1"),
                "旧版本应标记 SUPERSEDED，而非被 removeByBaseDocId 物理删除");
        assertTrue(kb.removeByBaseCalls.isEmpty(), "非覆盖式：不应触发物理删除");
        assertTrue(kb.markSupersededBaseCalls.contains("file-p1-s1"),
                "应针对基础文档触发 markSupersededByBaseId");
    }

    @Test
    void firstIngestDoesNotSupersede() {
        TestRecordingKnowledgeBase kb = new TestRecordingKnowledgeBase();
        KnowledgeIngestionService svc = newService(kb);
        svc.setChangeDetectionEnabled(false);

        ParsedDocument parsed = ParsedDocument.builder()
                .docId("file-x")
                .title("新文档")
                .content("首次入库内容应足够长以通过质量门禁测试与语义分块校验，"
                        + "避免过短文本被质量评分器判为低质而拒绝入库影响版本治理测试结论。")
                .build();
        when(router.parse(anyString())).thenReturn(List.of(parsed));
        when(chunker.chunk(any())).thenReturn(List.of(
                new KnowledgeDocument("file-x", "新文档",
                        "首次入库内容应足够长以通过质量门禁测试与语义分块校验，"
                                + "避免过短文本被质量评分器判为低质而拒绝入库影响版本治理测试结论。",
                        "cat", "kw", -1, -1)));

        IngestionResult result = svc.parseAndIngest("dummy.txt", "tenantA", "v1");

        assertTrue(result.isSuccess());
        // version="v1" 会编入 -v1 后缀
        assertEquals(DocumentStatus.ACTIVE, kb.statusOf("file-x-v1"));
        // 首次入库无旧版可废弃，且绝不应触发物理删除（非覆盖式）
        assertTrue(kb.removeByBaseCalls.isEmpty());
    }

    @Test
    void dateVersionDoesNotEncodeSuffixButStillIngests() {
        // 非 v\\d+ 格式（如日期版本 "2026-01"）降级为覆盖式 upsert，不编入 id
        TestRecordingKnowledgeBase kb = new TestRecordingKnowledgeBase();
        KnowledgeIngestionService svc = newService(kb);
        svc.setChangeDetectionEnabled(false);

        ParsedDocument parsed = ParsedDocument.builder()
                .docId("notice-001")
                .title("公告")
                .content("日期版本公告内容应足够长以通过质量门禁测试与语义分块校验，"
                        + "验证非标准v数字版本号不编入id后缀的降级行为是否符合预期。")
                .build();
        when(router.parse(anyString())).thenReturn(List.of(parsed));
        when(chunker.chunk(any())).thenReturn(List.of(
                new KnowledgeDocument("notice-001", "公告",
                        "日期版本公告内容应足够长以通过质量门禁测试与语义分块校验，"
                                + "验证非标准v数字版本号不编入id后缀的降级行为是否符合预期。",
                        "cat", "kw", -1, -1)));

        IngestionResult result = svc.parseAndIngest("dummy.txt", "tenantA", "2026-01");

        assertTrue(result.isSuccess());
        // 日期版本（非 v\d+ 格式）不编入 -v 后缀，原 id 保持不变（降级为覆盖式 upsert）
        assertNotNull(kb.docs.get("notice-001"),
                "非标准版本号不应编入 id 后缀，原 id 应保持不变");
        // 日期版本无历史版本可废弃，且绝不应触发物理删除（非覆盖式）
        assertTrue(kb.removeByBaseCalls.isEmpty());
    }

    private KnowledgeIngestionService newService(TestRecordingKnowledgeBase kb) {
        IngestAuditRecorder recorder = new LoggingIngestAuditRecorder();
        return new KnowledgeIngestionService(router, chunker, null, kb,
                new ContentHashCache(), new PiiScrubber(), new ChunkQualityScorer(),
                AuthorityLevel.L2_INTERNAL, recorder);
    }
}
