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
import com.example.smartassistant.common.rag.util.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 *
 * <p>变更检测（P0 改进，2026-07-02）：</p>
 * <ul>
 *   <li>使用 {@link ContentHashCache} 缓存文档级内容哈希</li>
 *   <li>摄入前检查 hash 是否变化，未变更的文档跳过解析/分块/入库</li>
 *   <li>使用 {@link HashUtil#normalizeAndHash(String)} 做规范化检测，
 *       避免页脚时间、广告位等假变更触发全量重建</li>
 * </ul>
 */
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final DocumentParseRouter router;
    private final DocumentChunker chunker;
    private final KnowledgeBase knowledgeBase;

    /** ⭐ 内容哈希缓存——用于变更检测 */
    private final ContentHashCache hashCache;

    /** 是否启用变更检测（默认 true） */
    private boolean changeDetectionEnabled = true;

    public KnowledgeIngestionService(DocumentParseRouter router,
                                      DocumentChunker chunker,
                                      KnowledgeBase knowledgeBase) {
        this(router, chunker, knowledgeBase, new ContentHashCache());
    }

    public KnowledgeIngestionService(DocumentParseRouter router,
                                      DocumentChunker chunker,
                                      KnowledgeBase knowledgeBase,
                                      ContentHashCache hashCache) {
        this.router = router;
        this.chunker = chunker;
        this.knowledgeBase = knowledgeBase;
        this.hashCache = hashCache != null ? hashCache : new ContentHashCache();
    }

    /** 启用或禁用变更检测 */
    public void setChangeDetectionEnabled(boolean enabled) {
        this.changeDetectionEnabled = enabled;
    }

    /** 获取内容哈希缓存实例（用于外部注入或清空） */
    public ContentHashCache getHashCache() {
        return hashCache;
    }

    /**
     * 解析并摄入文档。
     * <p>
     * 流程：
     * <ol>
     *   <li>文档解析（若变更检测开启且 hash 未变则跳过后续步骤）</li>
     *   <li>注入租户元数据</li>
     *   <li>语义分块</li>
     *   <li>注入租户 ID</li>
     *   <li>批量入库</li>
     *   <li>重建索引</li>
     * </ol>
     * </p>
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

            // ⭐ Step 1.5: 变更检测——基于 baseDocId 聚合 hash 跳过未变更文档
            if (changeDetectionEnabled) {
                // 按 baseDocId 分组
                Map<String, List<ParsedDocument>> byBase = parsed.stream()
                        .collect(Collectors.groupingBy(p -> extractBaseDocId(p.getDocId())));

                List<ParsedDocument> changedDocs = new ArrayList<>();
                List<String> unchangedBases = new ArrayList<>();

                for (Map.Entry<String, List<ParsedDocument>> entry : byBase.entrySet()) {
                    String baseDocId = entry.getKey();

                    // 计算该 baseDocId 下所有段落的聚合 hash
                    List<String> contents = entry.getValue().stream()
                            .map(ParsedDocument::getContent)
                            .filter(c -> c != null && !c.isBlank())
                            .toList();
                    String newHash = HashUtil.aggregateHash(contents);

                    if (hashCache.needsReingest(baseDocId, newHash)) {
                        // 文档已变更或首次摄入 → 标记为需要处理
                        changedDocs.addAll(entry.getValue());
                    } else {
                        unchangedBases.add(baseDocId);
                    }
                }

                if (changedDocs.isEmpty()) {
                    // 全部未变更 → 直接跳过
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("[Ingestion] 全部文档未变更，跳过: file={}, 耗时={}ms", filePath, elapsed);
                    return IngestionResult.skipped("全部未变更", 0, elapsed);
                }

                if (!unchangedBases.isEmpty()) {
                    log.info("[Ingestion] 部分跳过: unchanged={}, changed={}",
                            unchangedBases.size(), changedDocs.size());
                }

                // 用变更后的文档列表替换 parsed
                parsed = changedDocs;
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

            // ⭐ Step 7: 更新内容哈希缓存
            if (changeDetectionEnabled) {
                updateHashCache(parsed);
            }

            return new IngestionResult(docs.size(), elapsed, List.of());

        } catch (Exception e) {
            log.error("[Ingestion] 摄入失败: file={}, error={}", filePath, e.getMessage(), e);
            return IngestionResult.failed("摄入失败: " + e.getMessage());
        }
    }

    /**
     * 成功摄入后更新内容哈希缓存。
     */
    private void updateHashCache(List<ParsedDocument> parsed) {
        Map<String, List<ParsedDocument>> byBase = parsed.stream()
                .collect(Collectors.groupingBy(p -> extractBaseDocId(p.getDocId())));

        for (Map.Entry<String, List<ParsedDocument>> entry : byBase.entrySet()) {
            List<String> contents = entry.getValue().stream()
                    .map(ParsedDocument::getContent)
                    .filter(c -> c != null && !c.isBlank())
                    .toList();
            String newHash = HashUtil.aggregateHash(contents);
            hashCache.put(entry.getKey(), newHash);
        }
    }

    /** 提取基础文档 ID（去除块编号后缀、版本后缀） */
    private static String extractBaseDocId(String docId) {
        if (docId == null) return "";
        String base = docId;
        base = base.replaceAll("-g\\d+(-c\\d+)?$", "");
        base = base.replaceAll("-v\\d+(\\.\\d+)?$", "");
        base = base.replaceAll("-s\\d+$", "");
        return base;
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
