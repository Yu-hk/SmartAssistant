/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.rag.AuthorityLevel;
import com.example.smartassistant.common.rag.InMemoryKnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 摄入管线合规 + 缺陷验证（REQ-1 / 缺陷#3）。
 * <p>
 * 包含两个核心验证：
 * <ol>
 *   <li>{@link #ingestionDoesNotTriggerFullReindex()} —— 验证缺陷#3修复：摄入不再触发整库 reindex
 *       （以 validator=null 隔离 sourceType 干扰）；</li>
 *   <li>{@link #compliantDocumentShouldBeIngestedNotRejected()} —— 验证 REQ-1「合规文档断言通过」，
 *       会暴露一个<b>源码缺陷</b>：摄入流程从未把 contentType 映射的 sourceType 绑定到 KnowledgeDocument，
 *       导致 DocumentValidator 对所有文档返回 UNKNOWN_SOURCE，合规文档也被 100% 误拦。</li>
 * </ol>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeIngestionComplianceTest {

    @Mock
    private com.example.smartassistant.common.rag.document.DocumentParseRouter router;

    @Mock
    private BgeEmbeddingModel bge;

    private KnowledgeIngestionService newService(KnowledgeBase kb, ReviewQueueService rq,
                                                  DocumentValidator validator) {
        KnowledgeIngestionService svc = new KnowledgeIngestionService(
                router, null, null, kb,
                new ContentHashCache(), new PiiScrubber(), new ChunkQualityScorer(),
                AuthorityLevel.L2_INTERNAL, new LoggingIngestAuditRecorder());
        svc.setMetadataEnricher(new DocumentMetadataEnricher());
        // validator 可空：传入 null 表示不装载校验器，用于隔离「缺陷#3 不触发 reindex」的验证，
        // 避免 sourceType 源码缺陷（SOURCE BUG #1）干扰断言。
        if (validator != null) {
            svc.setValidator(validator);
        }
        svc.setReviewQueueService(rq);
        svc.setChangeDetectionEnabled(false);
        return svc;
    }

    private ParsedDocument compliantParsed(String contentType) {
        return ParsedDocument.builder()
                .docId("policy-001")
                .title("退款政策")
                .content("退款政策规定订单完成后7天内可申请退款，支持原路返回与余额退回两种渠道。"
                        + "退款申请将在审核通过后3个工作日内原路退回到支付账户，全程可实时查询进度与状态。")
                .contentType(contentType)
                .category("退款政策")
                .version("v2")
                .build();
    }

    // ───────────────────────── 缺陷#3：摄入不再触发整库 reindex ─────────────────────────

    @Test
    void ingestionDoesNotTriggerFullReindex() {
        // validator=null 隔离 sourceType 缺陷，仅验证「增量 upsert 不触发全量 reindex」
        ParsedDocument parsed = ParsedDocument.builder()
                .docId("doc-1")
                .title("文档")
                .content("足够长的合规正文内容用于通过质量门禁并验证摄入流程不再触发全量重算索引。"
                        + "该文本需满足信息密度与长度阈值，避免被质量评分器判为低质而拒绝入库影响结论。")
                .contentType("word")
                .category("分类")
                .version("v1")
                .build();
        when(router.parse(any())).thenReturn(List.of(parsed));

        KnowledgeBase kb = mock(KnowledgeBase.class);
        KnowledgeIngestionService svc = newService(kb, null, null); // validator=null 隔离 sourceType 缺陷

        IngestionResult result = svc.parseAndIngestWithValidation("doc.docx", "tenantA", "qa-bot");

        assertTrue(result.isSuccess(), "摄入应成功");
        verify(kb).addDocuments(anyList());
        verify(kb, never()).reindex(); // 关键：缺陷#3 修复后不应调用整库 reindex
    }

    // ───────────────────────── REQ-1：合规文档应入库（暴露源码缺陷） ─────────────────────────

    /**
     * ⚠️ 本测试断言「合规文档应通过校验并入库」。当前实现会失败——暴露源码缺陷：
     * 摄入流程（KnowledgeIngestionService.ingestInternal）从未调用
     * {@link DocumentMetadataEnricher#toSourceType(String)} 把 contentType 映射为 sourceType 并写入
     * KnowledgeDocument，导致 sourceType 恒为 ""，DocumentValidator 对所有文档返回 UNKNOWN_SOURCE，
     * 连合规文档也被 100% 拦截进复核队列，违反 PRD「合规文档断言通过」与「脏数据拦截率=100% 仅针对脏数据」。
     *
     * <p>修复方向（转工程师）：在 ingestInternal 构造 KnowledgeDocument 时
     * 用 {@code DocumentMetadataEnricher.toSourceType(p.getContentType())} 填充 sourceType。</p>
     */
    @Test
    void compliantDocumentShouldBeIngestedNotRejected() {
        when(router.parse(any())).thenReturn(List.of(compliantParsed("word")));

        InMemoryKnowledgeBase kb = new InMemoryKnowledgeBase("kb", bge, null, null);
        ReviewQueueService rq = new ReviewQueueService();
        KnowledgeIngestionService svc = newService(kb, rq, new DocumentValidator());

        IngestionResult result = svc.parseAndIngestWithValidation("policy.docx", "tenantA", "qa-bot");

        assertEquals(0, rq.pendingCount(),
                "合规文档不应进入复核队列（源码缺陷：sourceType 未从 contentType 绑定 → UNKNOWN_SOURCE 误拦）");
        assertEquals(1, kb.size(), "合规文档应成功入库");
    }
}
