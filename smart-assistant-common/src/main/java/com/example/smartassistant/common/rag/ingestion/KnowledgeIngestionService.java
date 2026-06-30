/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.chunking.DocumentChunker;
import com.example.smartassistant.common.rag.document.DocumentParseRouter;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 知识摄取编排服务——端到端封装文档解析、分块、入库流程。
 * <p>
 * 使用方式：
 * <pre>{@code
 * KnowledgeIngestionService ingestion = new KnowledgeIngestionService(router, chunker, knowledgeBase);
 * IngestionResult result = ingestion.parseAndIngest("/path/to/doc.pdf", "tenant_001");
 * // result.docCount() 返回入库文档数
 * // result.errors() 返回解析或分块中的错误
 * }</pre>
 *
 * <p>参考 RAG 文章的生产流程：
 * 文档解析 → 元素提取 → 元数据注入 → 语义分块 → 嵌入 → 向量入库 → 索引重建。
 * </p>
 */
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final DocumentParseRouter router;
    private final DocumentChunker chunker;
    private final KnowledgeBase knowledgeBase;

    public KnowledgeIngestionService(DocumentParseRouter router,
                                      DocumentChunker chunker,
                                      KnowledgeBase knowledgeBase) {
        this.router = router;
        this.chunker = chunker;
        this.knowledgeBase = knowledgeBase;
    }

    /**
     * 解析并摄入文档。
     *
     * @param filePath 文档路径
     * @param tenantId 租户 ID（空字符串表示公开文档）
     * @return 摄入结果
     */
    public IngestionResult parseAndIngest(String filePath, String tenantId) {
        long start = System.currentTimeMillis();
        log.info("[Ingestion] 开始摄入: file={}, tenantId={}", filePath, tenantId);

        try {
            // Step 1: 文档解析
            List<ParsedDocument> parsed = router.parse(filePath);
            if (parsed.isEmpty()) {
                log.warn("[Ingestion] 解析结果为空: {}", filePath);
                return IngestionResult.empty("文档解析结果为空，可能为空白文档或不支持的格式");
            }

            // Step 2: 注入租户元数据
            if (tenantId != null && !tenantId.isBlank()) {
                for (ParsedDocument doc : parsed) {
                    // ParsedDocument 不可变，通过分块后的 KnowledgeDocument 注入 tenantId
                }
            }

            // Step 3: 语义分块
            List<KnowledgeDocument> docs = chunker.chunk(parsed);

            // Step 4: 注入租户 ID（当解析阶段未指定时）
            if (tenantId != null && !tenantId.isBlank()) {
                docs = docs.stream()
                        .map(doc -> new KnowledgeDocument(
                                doc.getId(), doc.getTitle(), doc.getContent(),
                                doc.getCategory(), doc.getKeywords(),
                                doc.getEffectiveAt(), doc.getExpireAt(),
                                tenantId,
                                doc.getVersion(),
                                doc.getSourceUrl(),
                                doc.getChunkIndex()))
                        .toList();
            }

            // Step 5: 批量入库
            knowledgeBase.addDocuments(docs);

            // Step 6: 重建索引（确保向量 + BM25 索引一致）
            knowledgeBase.reindex();

            long elapsed = System.currentTimeMillis() - start;
            log.info("[Ingestion] 摄入完成: file={}, docs={}, 耗时={}ms",
                    filePath, docs.size(), elapsed);

            return new IngestionResult(docs.size(), elapsed, List.of());

        } catch (Exception e) {
            log.error("[Ingestion] 摄入失败: file={}, error={}", filePath, e.getMessage(), e);
            return IngestionResult.failed("摄入失败: " + e.getMessage());
        }
    }

    /**
     * 解析并摄入文档（默认公开租户）。
     */
    public IngestionResult parseAndIngest(String filePath) {
        return parseAndIngest(filePath, "");
    }

    /**
     * 判断文件格式是否被支持。
     */
    public boolean supports(String filePath) {
        return router.supports(filePath);
    }

    /**
     * 获取当前知识库文档总数。
     */
    public int knowledgeBaseSize() {
        return knowledgeBase.size();
    }
}
