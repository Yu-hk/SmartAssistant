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
import com.example.smartassistant.common.rag.store.KnowledgeIndexMetaService;
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
 *
 * <p>RAG 生产化改造（2026-07-14，REQ-1）：</p>
 * <ul>
 *   <li>注入 {@link DocumentMetadataEnricher}（版本/时效/分类/ACL/sourceType 绑定）；</li>
 *   <li>注入 {@link DocumentValidator} + {@link ReviewQueueService}（脏数据 100% 拦截入复核队列）；</li>
 *   <li>indexVersion 取自 {@link KnowledgeIndexMetaService} 的 active 版本（替代硬编码 v1）；</li>
 *   <li><b>去除每次摄入的强制全量 reindex</b>（架构缺陷 #3）：embedding 在 addDocument 内完成，
 *       摄入仅做增量 upsert，多实例互不触发重算（REQ-4）。</li>
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

    // ==================== RAG 生产化改造：摄入治理组件 ====================

    /** ⭐ 元数据绑定器（REQ-1，可空：空则跳过绑定） */
    private DocumentMetadataEnricher metadataEnricher;

    /** ⭐ 脏数据校验器（REQ-1，可空：空则跳过校验，全部放行） */
    private DocumentValidator validator;

    /** ⭐ 复核队列服务（REQ-1，可空：空则脏数据仅日志，不入队） */
    private ReviewQueueService reviewQueueService;

    /** ⭐ 索引版本元数据服务（REQ-2，可空：空则用 CURRENT_INDEX_VERSION） */
    private KnowledgeIndexMetaService indexMetaService;

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
     * {@link com.example.smartassistant.common.rag.graph.EntityExtractor}，如 {@code LlmEntityExtractor}）时才会实际抽取，
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

    // ==================== RAG 生产化改造：摄入治理组件注入 ====================

    /** 注入元数据绑定器（REQ-1） */
    public void setMetadataEnricher(DocumentMetadataEnricher enricher) {
        this.metadataEnricher = enricher;
    }

    /** 注入脏数据校验器（REQ-1） */
    public void setValidator(DocumentValidator validator) {
        this.validator = validator;
    }

    /** 注入复核队列服务（REQ-1） */
    public void setReviewQueueService(ReviewQueueService reviewQueueService) {
        this.reviewQueueService = reviewQueueService;
    }

    /** 注入索引版本元数据服务（REQ-2） */
    public void setIndexMetaService(KnowledgeIndexMetaService indexMetaService) {
        this.indexMetaService = indexMetaService;
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
     * 解析并摄入文档（默认公开租户）。
     */
    public IngestionResult parseAndIngest(String filePath) {
        return parseAndIngest(filePath, "");
    }

    /**
     * 解析并摄入文档（含租户）。
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
        return ingestInternal(filePath, tenantId, version, stageListener, null);
    }

    /**
     * ⭐ 解析并摄入（含脏数据拦截 + 复核分支），供 Webhook / 定时扫描 / 复核审批重投调用。
     * <p>
     * 内部复用 {@link #ingestInternal}；{@code submittedBy} 用于复核队列溯源（缺省回退 tenantId/system）。
     * </p>
     *
     * @param filePath    文档路径
     * @param tenantId    租户 ID
     * @param submittedBy 提交人（运营/系统），用于审计溯源
     */
    public IngestionResult parseAndIngestWithValidation(String filePath, String tenantId, String submittedBy) {
        return ingestInternal(filePath, tenantId, "v1", this.stageListener, submittedBy);
    }

    /**
     * 核心摄入逻辑（原 {@code parseAndIngest(filePath,tenantId,version,stageListener)} 的实现）。
     * <p>新增：元数据绑定、动态 indexVersion、脏数据校验→复核队列分支；并去除每次摄入的强制 reindex。</p>
     */
    private IngestionResult ingestInternal(String filePath, String tenantId, String version,
                                           Consumer<IngestionJobStatus> stageListener,
                                           String submittedBy) {
        Consumer<IngestionJobStatus> sl = stageListener != null ? stageListener : NOOP_LISTENER;
        long start = System.currentTimeMillis();
        if (version == null || version.isBlank()) version = "v1";

        // ⭐ 动态索引版本：取 active 版本打标（无服务则回退 CURRENT_INDEX_VERSION）
        String activeIndexVersion = (indexMetaService != null)
                ? indexMetaService.getActiveVersion() : CURRENT_INDEX_VERSION;

        log.info("[Ingestion] 开始摄入: file={}, tenantId={}, version={}, indexVersion={}",
                filePath, tenantId, version, activeIndexVersion);

        try {
            // Step 1: 文档解析
            List<ParsedDocument> parsed = router.parse(filePath);
            if (parsed.isEmpty()) {
                log.warn("[Ingestion] 解析结果为空: {}", filePath);
                return IngestionResult.empty("文档解析结果为空，可能为空白文档或不支持的格式");
            }
            // ⭐ 阶段回调：解析完成
            sl.accept(IngestionJobStatus.PARSING);

            // ⭐ Step 1.2：元数据绑定（REQ-1）——补全 sourceType/version/effectiveAt/expireAt/category
            if (metadataEnricher != null) {
                parsed = parsed.stream()
                        .map(metadataEnricher::enrich)
                        .collect(Collectors.toList());
            }

            // ⭐ Step 1.5: 变更检测——基于 baseDocId 聚合 hash 跳过未变更文档
            if (changeDetectionEnabled) {
                Map<String, List<ParsedDocument>> byBase = parsed.stream()
                        .collect(Collectors.groupingBy(p -> extractBaseDocId(p.getDocId())));

                List<ParsedDocument> changedDocs = new ArrayList<>();
                List<String> unchangedBases = new ArrayList<>();

                for (Map.Entry<String, List<ParsedDocument>> entry : byBase.entrySet()) {
                    String baseDocId = entry.getKey();
                    List<String> contents = entry.getValue().stream()
                            .map(ParsedDocument::getContent)
                            .filter(c -> c != null && !c.isBlank())
                            .toList();
                    String newHash = HashUtil.aggregateHash(contents);

                    if (hashCache.needsReingest(baseDocId, newHash)) {
                        changedDocs.addAll(entry.getValue());
                    } else {
                        unchangedBases.add(baseDocId);
                    }
                }

                if (changedDocs.isEmpty()) {
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("[Ingestion] 全部文档未变更，跳过: file={}, 耗时={}ms", filePath, elapsed);
                    return IngestionResult.skipped("全部未变更", 0, elapsed);
                }
                if (!unchangedBases.isEmpty()) {
                    log.info("[Ingestion] 部分跳过: unchanged={}, changed={}",
                            unchangedBases.size(), changedDocs.size());
                }
                parsed = changedDocs;
            }

            // ⭐ Step 1.8: 知识图谱实体/关系抽取（联动）
            extractEntitiesToGraph(parsed, tenantId);

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
                            activeIndexVersion))
                    .toList();

            // ⭐ Step 4.2: 入库前质检 pipeline（对标字节 RAG 七连问第二问）
            // ① PII 脱敏 → ② Chunk 质量评分门禁 → ③ 脏数据校验(REQ-1) → 复核队列
            List<KnowledgeDocument> qualityPassed = new ArrayList<>();
            int scrubbed = 0;
            int rejected = 0;
            String operator = (submittedBy != null && !submittedBy.isBlank()) ? submittedBy
                    : (tenantId != null && !tenantId.isBlank() ? tenantId : "system");
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
                        activeIndexVersion);

                // ② 质量评分门禁：低于阈值不入库，避免污染检索
                if (!qualityScorer.isQualified(scrubbedDoc)) {
                    rejected++;
                    log.debug("[Ingestion] 低质 chunk 跳过入库: id={}, score={}",
                            doc.getId(), qualityScorer.score(scrubbedDoc));
                    continue;
                }

                // ③ ⭐ 脏数据校验（REQ-1）：命中即 100% 拦截入复核队列，不入库
                if (validator != null) {
                    ValidationResult vr = validator.validate(scrubbedDoc);
                    if (!vr.isPassed()) {
                        rejected++;
                        if (reviewQueueService != null) {
                            reviewQueueService.enqueue(
                                    ReviewItem.of(scrubbedDoc, vr.getReason(), vr.getCode(), operator));
                        }
                        log.warn("[Ingestion] 脏数据拦截: id={}, code={}, reason={}",
                                scrubbedDoc.getId(), vr.getCode(), vr.getReason());
                        continue;
                    }
                }

                qualityPassed.add(scrubbedDoc);
            }
            if (rejected > 0) {
                log.info("[Ingestion] 质检完成: scrubbed={}, rejected(低质/脏数据)={}, passed={}",
                        scrubbed, rejected, qualityPassed.size());
            }
            docs = qualityPassed;

            // ⭐ Step 4.2: chunk 级增量 diff（P2-a）
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
            for (KnowledgeDocument newDoc : docs) {
                String baseDocId = newDoc.getBaseDocId();
                if (baseDocId == null || baseDocId.isBlank()) continue;
                knowledgeBase.markSupersededByBaseId(baseDocId, newDoc.getId());
                auditRecorder.record(IngestAuditEvent.of(operator, "SUPERSEDE",
                        baseDocId, version, "keep=" + newDoc.getId()));
            }

            // Step 5: 批量入库（增量 upsert，embedding 在 addDocument 内完成）
            knowledgeBase.addDocuments(docs);
            // ⭐ 阶段回调：向量化（嵌入在 addDocument 内完成）与入库已结束
            sl.accept(IngestionJobStatus.EMBEDDING);

            // ⭐ Step 5.5: 入库审计（P0）
            auditRecorder.record(IngestAuditEvent.of(operator, "INGEST", "", version,
                    "docs=" + docs.size()));

            // ⭐ 修复架构缺陷 #3：去除每次摄入的强制全量 reindex（增量 upsert 即可；
            //    PG 多实例共享 + 不互相触发重算，满足 REQ-2/REQ-4）

            long elapsed = System.currentTimeMillis() - start;
            log.info("[Ingestion] 摄入完成: file={}, docs={}, 耗时={}ms",
                    filePath, docs.size(), elapsed);

            // ⭐ Step 7: 更新内容哈希缓存
            if (changeDetectionEnabled) {
                updateHashCache(parsed);
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
     */
    private static String withVersionSuffix(String id, String version) {
        if (version == null || version.isBlank()) version = "v1";
        if (!version.matches("v\\d+(\\.\\d+)?")) return id;
        if (id.matches(".*-v\\d+(\\.\\d+)?$")) return id;
        return id + "-" + version;
    }

    /**
     * ⭐ 触发缓存失效回调（知识库更新后调用）。
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
