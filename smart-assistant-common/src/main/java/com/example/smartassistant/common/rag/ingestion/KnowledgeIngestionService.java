/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.AuthorityLevel;
import com.example.smartassistant.common.rag.DocumentStatus;
import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.chunking.DocumentChunker;
import com.example.smartassistant.common.rag.chunking.ParentChildDocumentChunker;
import com.example.smartassistant.common.rag.document.DocumentParseRouter;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import com.example.smartassistant.common.rag.graph.KnowledgeGraphService;
import com.example.smartassistant.common.rag.multimodal.ImageCaptioner;
import com.example.smartassistant.common.rag.multimodal.ImageReference;
import com.example.smartassistant.common.rag.multimodal.MultimodalIngestor;
import com.example.smartassistant.common.rag.multimodal.NoopImageCaptioner;
import com.example.smartassistant.common.rag.ingestion.job.IngestionJobStatus;
import com.example.smartassistant.common.rag.util.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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

    /** ⭐ 当前索引版本（chunk策略/embedding模型/解析策略变化时递增，文章⑦索引重建与向量库同步） */
    private static final String CURRENT_INDEX_VERSION = "v1";

    private final DocumentParseRouter router;
    private final DocumentChunker chunker;

    /** ⭐ Parent-Child 双粒度分块器（可选，非 null 时替代 chunker） */
    private final ParentChildDocumentChunker parentChildChunker;

    private final KnowledgeBase knowledgeBase;

    /** ⭐ 内容哈希缓存——用于变更检测 */
    private final ContentHashCache hashCache;

    /** ⭐ 缓存失效回调——知识库更新后自动失效相关缓存 */
    private final java.util.List<Runnable> cacheInvalidationHooks;

    /** ⭐ 入库前质检：PII 脱敏器 */
    private final PiiScrubber piiScrubber;

    /** ⭐ 入库前质检：Chunk 质量评分器 */
    private final ChunkQualityScorer qualityScorer;

    /** ⭐ 阶段回调（P0 异步任务管线用）——默认无操作；parseAndIngest 各阶段会回调以驱动状态机 */
    private Consumer<IngestionJobStatus> stageListener = NOOP_LISTENER;
    private static final Consumer<IngestionJobStatus> NOOP_LISTENER = s -> {};

    /** ⭐ 来源权威性等级（摄取时打标签，默认 L2 内部正式文档） */
    private final AuthorityLevel authorityLevel;

    /** ⭐ 入库操作审计记录器（P0，2026-07-07） */
    private final IngestAuditRecorder auditRecorder;

    /** ⭐ 知识图谱服务（可选）— 摄取时联动抽取实体/关系；未注入则跳过图谱构建 */
    private KnowledgeGraphService knowledgeGraphService;

    /** ⭐ 图片描述器（可选）— 多模态摄取（图片→视觉描述→入库）；未注入则跳过多模态 */
    private ImageCaptioner imageCaptioner = new NoopImageCaptioner();

    /** 是否启用变更检测（默认 true） */
    private boolean changeDetectionEnabled = true;

    public KnowledgeIngestionService(DocumentParseRouter router,
                                      DocumentChunker chunker,
                                      KnowledgeBase knowledgeBase) {
        this(router, chunker, null, knowledgeBase, new ContentHashCache());
    }

    /**
     * ⭐ 支持 Parent-Child 双粒度分块的构造器。
     * <p>
     * 当提供 {@code parentChildChunker} 时，使用 Parent-Child 策略代替普通分块：
     * 子块（256 tokens）用于向量检索，父块（1024 tokens）用于 LLM 阅读。
     * </p>
     */
    public KnowledgeIngestionService(DocumentParseRouter router,
                                      DocumentChunker chunker,
                                      ParentChildDocumentChunker parentChildChunker,
                                      KnowledgeBase knowledgeBase) {
        this(router, chunker, parentChildChunker, knowledgeBase, new ContentHashCache());
    }

    public KnowledgeIngestionService(DocumentParseRouter router,
                                      DocumentChunker chunker,
                                      ParentChildDocumentChunker parentChildChunker,
                                      KnowledgeBase knowledgeBase,
                                      ContentHashCache hashCache) {
        this(router, chunker, parentChildChunker, knowledgeBase, hashCache,
                new PiiScrubber(), new ChunkQualityScorer(), AuthorityLevel.L2_INTERNAL,
                new LoggingIngestAuditRecorder());
    }

    /**
     * ⭐ 全参构造器——支持注入入库前质检组件与来源权威性等级。
     *
     * @param piiScrubber    PII 脱敏器（null → 默认开启）
     * @param qualityScorer  Chunk 质量评分器（null → 默认开启）
     * @param authorityLevel 来源权威性等级（null → L2_INTERNAL）
     */
    public KnowledgeIngestionService(DocumentParseRouter router,
                                      DocumentChunker chunker,
                                      ParentChildDocumentChunker parentChildChunker,
                                      KnowledgeBase knowledgeBase,
                                      ContentHashCache hashCache,
                                      PiiScrubber piiScrubber,
                                      ChunkQualityScorer qualityScorer,
                                      AuthorityLevel authorityLevel,
                                      IngestAuditRecorder auditRecorder) {
        this.router = router;
        this.chunker = chunker;
        this.parentChildChunker = parentChildChunker;
        this.knowledgeBase = knowledgeBase;
        this.hashCache = hashCache != null ? hashCache : new ContentHashCache();
        this.cacheInvalidationHooks = new java.util.ArrayList<>();
        this.piiScrubber = piiScrubber != null ? piiScrubber : new PiiScrubber();
        this.qualityScorer = qualityScorer != null ? qualityScorer : new ChunkQualityScorer();
        this.authorityLevel = authorityLevel != null ? authorityLevel : AuthorityLevel.L2_INTERNAL;
        this.auditRecorder = auditRecorder != null ? auditRecorder : new LoggingIngestAuditRecorder();
    }

    /** ⭐ 注册缓存失效回调——知识库更新后自动调用 */
    public void addCacheInvalidationHook(Runnable hook) {
        if (hook != null) {
            this.cacheInvalidationHooks.add(hook);
        }
    }

    /**
     * ⭐ 注入知识图谱服务——摄取时联动抽取实体/关系写入图谱。
     * <p>
     * 仅当 {@link KnowledgeGraphService#getExtractor()} 可用（即装配了真实
     * {@link EntityExtractor}，如 {@code LlmEntityExtractor}）时才会实际抽取，
     * 否则该服务为空操作，不影响摄取性能。
     * </p>
     */
    public void setKnowledgeGraphService(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /**
     * ⭐ 注入图片描述器——启用多模态摄取（图片经视觉描述后入库）。
     * <p>
     * 默认 {@link NoopImageCaptioner}（空操作）。仅当注入可用描述器
     * （如 {@code OllamaVisionImageCaptioner}）时，{@link #ingestImages} 才会实际摄取。
     * </p>
     */
    public void setImageCaptioner(ImageCaptioner imageCaptioner) {
        this.imageCaptioner = imageCaptioner != null ? imageCaptioner : new NoopImageCaptioner();
    }

    /** 启用或禁用变更检测 */
    public void setChangeDetectionEnabled(boolean enabled) {
        this.changeDetectionEnabled = enabled;
    }

    /**
     * ⭐ 设置阶段回调（供异步任务管线监听解析/分块/向量化阶段，驱动状态机）。
     * <p>传入 {@code null} 视为无操作。</p>
     */
    public void setStageListener(Consumer<IngestionJobStatus> stageListener) {
        this.stageListener = stageListener != null ? stageListener : NOOP_LISTENER;
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
        return parseAndIngest(filePath, tenantId, "v1");
    }

    /**
     * 解析并摄入文档（含版本控制，支持非覆盖式版本）。
     *
     * @param version 文档版本（如 "v2"），编入 chunk id 以支持历史版本保留与回滚；
     *                非 {@code v\d+} 格式（如日期版本）不编入 id（降级为 upsert 覆盖）
     */
    public IngestionResult parseAndIngest(String filePath, String tenantId, String version) {
        return parseAndIngest(filePath, tenantId, version, this.stageListener);
    }

    /**
     * 解析并摄入文档（含阶段回调，供异步任务管线驱动状态机）。
     *
     * @param stageListener 阶段监听器，在解析/分块/向量化完成时回调；为 {@code null} 视为无操作
     */
    public IngestionResult parseAndIngest(String filePath, String tenantId, String version,
                                          Consumer<IngestionJobStatus> stageListener) {
        Consumer<IngestionJobStatus> sl = stageListener != null ? stageListener : NOOP_LISTENER;
        long start = System.currentTimeMillis();
        if (version == null || version.isBlank()) version = "v1";
        log.info("[Ingestion] 开始摄入: file={}, tenantId={}, version={}", filePath, tenantId, version);

        try {
            // Step 1: 文档解析
            List<ParsedDocument> parsed = router.parse(filePath);
            if (parsed.isEmpty()) {
                log.warn("[Ingestion] 解析结果为空: {}", filePath);
                return IngestionResult.empty("文档解析结果为空，可能为空白文档或不支持的格式");
            }
            // ⭐ 阶段回调：解析完成
            sl.accept(IngestionJobStatus.PARSING);

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

            // ⭐ Step 1.8: 知识图谱实体/关系抽取（联动）—
            //   仅当 knowledgeGraphService 已注入且其实体抽取器可用（如 LlmEntityExtractor）时生效，
            //   否则为空操作，不影响摄取性能。
            extractEntitiesToGraph(parsed, tenantId);

            // Step 2: 注入租户元数据
            if (tenantId != null && !tenantId.isBlank()) {
                for (ParsedDocument doc : parsed) {
                    // ParsedDocument 不可变，通过分块后的 KnowledgeDocument 注入 tenantId
                }
            }

            // Step 3: ⭐ 按 contentType 分流 — 结构化类型跳过 chunking 直接入库
            List<ParsedDocument> textDocs = new ArrayList<>();
            List<ParsedDocument> structuredDocs = new ArrayList<>();
            for (ParsedDocument p : parsed) {
                String ct = p.getContentType();
                if ("pdf-table".equals(ct) || "html".equals(ct) || "word".equals(ct)) {
                    structuredDocs.add(p);
                } else {
                    textDocs.add(p);
                }
            }

            List<KnowledgeDocument> docs = new ArrayList<>();

            // 结构化文档：不 chunk，整篇为独立 KnowledgeDocument
            for (ParsedDocument p : structuredDocs) {
                docs.add(new KnowledgeDocument(
                        p.getDocId(), p.getTitle(), p.getContent(),
                        p.getCategory(), p.getKeywords(),
                        p.getEffectiveAt(), p.getExpireAt(),
                        p.getTenantId(), p.getVersion(), p.getSourceUrl(), 0));
            }

            // 文本类文档：走分块器
            List<KnowledgeDocument> chunkedDocs;
            if (!textDocs.isEmpty()) {
                if (parentChildChunker != null) {
                    // ⭐ Parent-Child 策略：子块建索引（检索用），父块也入库（通过 parentDocId 关联）
                    ParentChildDocumentChunker.ParentChildResult pcResult =
                            parentChildChunker.chunkParentChild(textDocs);
                    List<KnowledgeDocument> childDocs = pcResult.childDocs();
                    List<KnowledgeDocument> parentDocs = pcResult.parentDocs();
                    chunkedDocs = new ArrayList<>(childDocs.size() + parentDocs.size());
                    chunkedDocs.addAll(childDocs);
                    chunkedDocs.addAll(parentDocs);
                    log.info("[Ingestion] Parent-Child 分块: children={}, parents={}, structured={}",
                            childDocs.size(), parentDocs.size(), structuredDocs.size());
                } else {
                    chunkedDocs = chunker.chunk(textDocs);
                    log.info("[Ingestion] 分块完成: docs={}, structured={}",
                            chunkedDocs.size(), structuredDocs.size());
                }
                docs.addAll(chunkedDocs);
            } else {
                log.info("[Ingestion] 全为结构化文档（跳过 chunking）: count={}", structuredDocs.size());
            }
            // ⭐ 阶段回调：分块完成
            sl.accept(IngestionJobStatus.CHUNKING);

            // Step 4: 注入租户 ID + 编入版本到 chunk id（非覆盖式版本，P0）
            final String ver = version;
            docs = docs.stream()
                    .map(doc -> new KnowledgeDocument(
                            withVersionSuffix(doc.getId(), ver),
                            doc.getTitle(), doc.getContent(),
                            doc.getCategory(), doc.getKeywords(),
                            doc.getEffectiveAt(), doc.getExpireAt(),
                            tenantId != null && !tenantId.isBlank() ? tenantId : doc.getTenantId(),
                            doc.getVersion(),
                            doc.getSourceUrl(),
                            doc.getChunkIndex(),
                            doc.getParentDocId(),
                            AuthorityLevel.L2_INTERNAL, DocumentStatus.ACTIVE,
                            CURRENT_INDEX_VERSION))
                    .toList();

            // ⭐ Step 4.2: 入库前质检 pipeline（对标字节 RAG 七连问第二问）
            // ① PII 脱敏 → ② Chunk 质量评分门禁 → ③ 注入来源权威性 + 状态
            List<KnowledgeDocument> qualityPassed = new ArrayList<>();
            int scrubbed = 0;
            int rejected = 0;
            for (KnowledgeDocument doc : docs) {
                // ① PII 脱敏（覆盖式，保留语义结构）
                String cleanContent = piiScrubber.scrub(doc.getContent());
                if (!cleanContent.equals(doc.getContent())) scrubbed++;

                KnowledgeDocument scrubbedDoc = new KnowledgeDocument(
                        doc.getId(), doc.getTitle(), cleanContent,
                        doc.getCategory(), doc.getKeywords(),
                        doc.getEffectiveAt(), doc.getExpireAt(),
                        doc.getTenantId(), doc.getVersion(),
                        doc.getSourceUrl(), doc.getChunkIndex(),
                        doc.getParentDocId(),
                        authorityLevel, DocumentStatus.ACTIVE,
                        CURRENT_INDEX_VERSION);

                // ② 质量评分门禁：低于阈值不入库，避免污染检索
                if (qualityScorer.isQualified(scrubbedDoc)) {
                    qualityPassed.add(scrubbedDoc);
                } else {
                    rejected++;
                    log.debug("[Ingestion] 低质 chunk 跳过入库: id={}, score={}",
                            doc.getId(), qualityScorer.score(scrubbedDoc));
                }
            }
            if (rejected > 0) {
                log.info("[Ingestion] 质检完成: scrubbed={}, rejected(低质)={}, passed={}",
                        scrubbed, rejected, qualityPassed.size());
            }
            docs = qualityPassed;

            // ⭐ Step 4.2: chunk 级增量 diff（P2-a）——仅对内容变更的 chunk 重新摄入，
            // 跳过未变更 chunk 的重复向量化，降低大文档微调成本。
            if (changeDetectionEnabled) {
                List<KnowledgeDocument> changedDocs = new ArrayList<>();
                int skipped = 0;
                for (KnowledgeDocument d : docs) {
                    String h = HashUtil.normalizeAndHash(d.getContent());
                    if (hashCache.hasChunkChanged(d.getBaseDocId(), h)) {
                        changedDocs.add(d);
                    } else {
                        skipped++;
                        log.debug("[Ingestion] chunk 级增量：跳过未变更 chunk: {}", d.getId());
                    }
                }
                if (skipped > 0) {
                    log.info("[Ingestion] chunk 级增量：跳过 {} 个未变更 chunk，仅重新摄入 {} 个",
                            skipped, changedDocs.size());
                }
                docs = changedDocs;
            }

            // ⭐ Step 4.5: 非覆盖式版本——标记旧版 SUPERSEDED，而非物理删除（P0）
            String operator = (tenantId != null && !tenantId.isBlank()) ? tenantId : "system";
            for (KnowledgeDocument newDoc : docs) {
                String baseDocId = newDoc.getBaseDocId();
                if (baseDocId == null || baseDocId.isBlank()) continue;
                knowledgeBase.markSupersededByBaseId(baseDocId, newDoc.getId());
                auditRecorder.record(IngestAuditEvent.of(operator, "SUPERSEDE",
                        baseDocId, version, "keep=" + newDoc.getId()));
            }

            // Step 5: 批量入库
            knowledgeBase.addDocuments(docs);
            // ⭐ 阶段回调：向量化（嵌入在 addDocument 内完成）与入库已结束
            sl.accept(IngestionJobStatus.EMBEDDING);

            // ⭐ Step 5.5: 入库审计（P0）
            auditRecorder.record(IngestAuditEvent.of(operator, "INGEST", "", version,
                    "docs=" + docs.size()));

            // Step 6: 重建索引（确保向量 + BM25 索引一致）
            knowledgeBase.reindex();

            long elapsed = System.currentTimeMillis() - start;
            log.info("[Ingestion] 摄入完成: file={}, docs={}, 耗时={}ms",
                    filePath, docs.size(), elapsed);

            // ⭐ Step 7: 更新内容哈希缓存
            if (changeDetectionEnabled) {
                updateHashCache(parsed);
                // ⭐ chunk 级哈希（P2-a）：仅变更的 chunk 在 docs 中，写回其哈希；
                // 未变更 chunk 的哈希已在上一轮缓存中，无需重写。
                for (KnowledgeDocument d : docs) {
                    hashCache.putChunk(d.getBaseDocId(), HashUtil.normalizeAndHash(d.getContent()));
                }
            }

            // ⭐ Step 8: 失效相关缓存
            fireCacheInvalidationHooks();

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

    /**
     * ⭐ 将解析文档的实体 / 关系抽取进知识图谱（仅在图谱服务可用时生效）。
     * <p>
     * 过短段落（&lt;30 字）跳过以避免无效 LLM 调用；图谱服务的抽取器不可用时
     * （{@link EntityExtractor#isAvailable()} 为 false）内部自动降级为空操作。
     * </p>
     */
    private void extractEntitiesToGraph(List<ParsedDocument> parsedDocs, String tenantId) {
        if (knowledgeGraphService == null) return;
        int triggered = 0;
        for (ParsedDocument p : parsedDocs) {
            String content = p.getContent();
            if (content == null || content.length() < 30) continue;
            knowledgeGraphService.extractFromDocument(content, p.getDocId(), tenantId);
            triggered++;
        }
        if (triggered > 0) {
            log.info("[Ingestion] 触发实体关系抽取: docs={}", triggered);
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
     * 将版本编入 chunk id 后缀（仅 {@code v\d+} 格式），实现非覆盖式版本。
     * <p>
     * 例如 id="file-p1-s1", version="v2" → "file-p1-s1-v2"。
     * 旧版 id（无后缀或旧版本后缀）经 {@link KnowledgeDocument#getBaseDocId()} 解析后
     * 与新版 baseDocId 一致，可被 {@code markSupersededByBaseId} 定位标记。
     * </p>
     * <p>
     * 非 {@code v\d+} 格式（如日期版本 "2026-01"）不编入 id，降级为 upsert 覆盖
     * （与旧行为一致，避免破坏 baseDocId 解析）。
     * </p>
     */
    private static String withVersionSuffix(String id, String version) {
        if (version == null || version.isBlank()) version = "v1";
        if (!version.matches("v\\d+(\\.\\d+)?")) return id;
        if (id.matches(".*-v\\d+(\\.\\d+)?$")) return id;
        return id + "-" + version;
    }

    /**
     * ⭐ 触发缓存失效回调（知识库更新后调用）。
     * <p>
     * 注册方（如 {@code AnswerCacheService}）实现 {@link Runnable#run()} 来失效相关缓存。
     * 确保知识库变更后，旧的缓存回答不再被返回。
     * </p>
     */
    private void fireCacheInvalidationHooks() {
        for (Runnable hook : cacheInvalidationHooks) {
            try {
                hook.run();
            } catch (Exception e) {
                log.warn("[Ingestion] 缓存失效回调异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 解析并摄入文档（默认公开租户）。
     */
    public IngestionResult parseAndIngest(String filePath) {
        return parseAndIngest(filePath, "");
    }

    /**
     * ⭐ 多模态摄取——将图片经视觉描述转为知识文档入库。
     * <p>
     * 仅当 {@link ImageCaptioner#isAvailable()} 为 true 时生效；描述器为空操作或
     * 图片描述为空时直接返回 0，不改变知识库。摄取成功后触发缓存失效 + 索引重建，
     * 与文本摄取 {@link #parseAndIngest} 保持一致的最终态。
     * </p>
     *
     * @param images   图片引用列表
     * @param tenantId 租户 ID（空串表示公开）
     * @return 实际入库的图片数（0 表示未摄取）
     */
    public int ingestImages(List<ImageReference> images, String tenantId) {
        if (images == null || images.isEmpty()) {
            return 0;
        }
        if (imageCaptioner == null || !imageCaptioner.isAvailable()) {
            log.debug("[Ingestion] 图片描述器不可用，跳过多模态摄取");
            return 0;
        }
        MultimodalIngestor ingestor = new MultimodalIngestor(imageCaptioner, knowledgeBase);
        int ingested = ingestor.ingestImages(images, tenantId);
        if (ingested > 0) {
            String operator = (tenantId != null && !tenantId.isBlank()) ? tenantId : "system";
            auditRecorder.record(IngestAuditEvent.of(
                    operator, "INGEST_MULTIMODAL", "", "v1", "imgs=" + ingested));
            knowledgeBase.reindex();
            fireCacheInvalidationHooks();
        }
        return ingested;
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
