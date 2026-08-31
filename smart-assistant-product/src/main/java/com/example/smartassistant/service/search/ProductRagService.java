/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.pipeline.RagSearchPipeline;
import com.example.smartassistant.config.NativeRagProperties;
import com.example.smartassistant.spi.ProductBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ⭐ 商品多路 RAG 检索服务。
 * <p>
 * 已重构为 Pipeline 模式，检索逻辑委派给 {@link RagSearchPipeline}。
 * 保留 {@link #retrieve(String)} 和 {@link #retrieveWithQuality(String)} 兼容旧调用。
 * 新增 {@link #retrieveWithQualityResult(String)} 返回 {@link RetrievalQualityResult} 共享模型。
 * </p>
 *
 * <p>Pipeline Handler 链（按 Order 执行）：
 * <ol>
 *   <li><b>MultiQueryHandler</b> (Order=0) — 可选，Multi-Query 查询扩展</li>
 *   <li><b>ExactMatchHandler</b> (Order=10) — 精确匹配</li>
 *   <li><b>KeywordSearchHandler</b> (Order=20) — 关键词搜索</li>
 *   <li><b>Bm25SearchHandler</b> (Order=30) — BM25 语义评分</li>
 *   <li><b>KnowledgeSearchHandler</b> (Order=40) — 经验知识库检索</li>
 *   <li><b>GraphSearchHandler</b> (Order=50) — Graph 图检索</li>
 *   <li><b>RrfFusionHandler</b> (Order=100) — RRF 融合 + 质量评估</li>
 * </ol>
 * </p>
 */
@Service
public class ProductRagService {

    private static final Logger log = LoggerFactory.getLogger(ProductRagService.class);

    private final RagSearchPipeline pipeline;
    private final KnowledgeScopeSelector scopeSelector;
    private final SupplementalQueryPlanner supplementalQueryPlanner;
    private final NativeRagProperties properties;

    /** Backward-compatible constructor used by focused tests and embedded consumers. */
    public ProductRagService(RagSearchPipeline pipeline,
                             ProductBackend productBackend) {
        this(pipeline,
                (agentName, skills, query) -> new KnowledgeScopeSelector.KnowledgeScope(
                        List.of(com.example.smartassistant.common.rag.KnowledgeSeedData.PRODUCT_KB),
                        "product-default"),
                (SupplementalQueryPlanner) null,
                new NativeRagProperties());
    }

    @Autowired
    public ProductRagService(RagSearchPipeline pipeline,
                             KnowledgeScopeSelector scopeSelector,
                             ObjectProvider<SupplementalQueryPlanner> plannerProvider,
                             NativeRagProperties properties) {
        this(pipeline, scopeSelector, plannerProvider.getIfAvailable(), properties);
    }

    ProductRagService(RagSearchPipeline pipeline,
                      KnowledgeScopeSelector scopeSelector,
                      SupplementalQueryPlanner supplementalQueryPlanner,
                      NativeRagProperties properties) {
        this.pipeline = pipeline;
        this.scopeSelector = scopeSelector;
        this.supplementalQueryPlanner = supplementalQueryPlanner;
        this.properties = properties;
    }

    /**
     * 多路 RAG 检索 + RRF 融合（兼容旧调用，无质量评估）。
     *
     * <p>新代码请使用 {@link #retrieveWithQualityResult(String)} 以获取结构化质量结果。</p>
     *
     * @param query 用户查询文本
     * @return 融合后的检索结果字符串（质量低时可能返回空字符串）
     */
    public String retrieve(String query) {
        RetrievalResult result = retrieveWithQuality(query);
        return result.highQuality() ? result.content() : "";
    }

    /**
     * P1 RAG 质量评分结果（兼容旧调用，保留向后兼容性）。
     */
    public record RetrievalResult(
            String content,
            double qualityScore,
            boolean highQuality,
            String fallback
    ) {}

    /**
     * 带质量评估的 RAG 检索——委派给 {@link RagSearchPipeline}（兼容旧调用）。
     *
     * @deprecated 请使用 {@link #retrieveWithQualityResult(String)} 返回共享模型
     */
    @Deprecated
    public RetrievalResult retrieveWithQuality(String query) {
        RetrievalQualityResult qr = retrieveWithQualityResult(query);
        return new RetrievalResult(
                qr.getContent(),
                qr.getNormalizedScore(),
                qr.isHighQuality(),
                qr.isRejected() ? qr.getRejectionCode() + ": " + qr.getRejectionMessage() : null
        );
    }

    /**
     * 带结构化质量评估的 RAG 检索——返回 {@link RetrievalQualityResult} 共享模型。
     *
     * <p>返回包含归一化质量分数、结构化拒答原因和用户友好的拒绝消息。</p>
     *
     * @param query 用户查询
     * @return 结构化检索质量结果
     */
    public RetrievalQualityResult retrieveWithQualityResult(String query) {
        if (query == null || query.isBlank()) {
            return RetrievalQualityResult.noData("空查询");
        }

        KnowledgeScopeSelector.KnowledgeScope scope = scopeSelector.select(
                "product", List.of("product-search", "product-recommendation"), query);
        Attempt best = null;
        String currentQuery = query.strip();
        int attemptsExecuted = 0;
        int maxAttempts = properties.isEnabled() && supplementalQueryPlanner != null
                ? properties.getMaxAttempts() : 1;

        for (int attemptNo = 1; attemptNo <= maxAttempts; attemptNo++) {
            attemptsExecuted = attemptNo;
            RagSearchContext context = executeAttempt(currentQuery, query, scope, attemptNo);
            Attempt candidate = new Attempt(currentQuery, context);
            if (best == null || isBetter(candidate.context(), best.context())) {
                best = candidate;
            }
            if (hasSufficientEvidence(context) || attemptNo >= maxAttempts) {
                break;
            }
            String supplement = supplementalQueryPlanner.plan(
                    query, currentQuery, evidenceSummary(context), attemptNo + 1);
            if (supplement == null || supplement.isBlank() || supplement.equalsIgnoreCase(currentQuery)) {
                break;
            }
            currentQuery = supplement.strip();
        }

        RagSearchContext ctx = best != null ? best.context() : executeAttempt(query, query, scope, 1);

        // 从 Pipeline 结果构建返回
        List<RagSearchContext.RankedItem> fused = ctx.getFusedResults();
        double qualityScore = ctx.getQualityScore();

        if (fused.isEmpty()) {
            log.warn("[ProductRAG] ⚠️ Pipeline 全部路径未召回: query={}, 耗时={}ms",
                    query, ctx.getElapsedMs());
            return RetrievalQualityResult.noData(query);
        }

        boolean highQuality = qualityScore >= ctx.getQualityThreshold();

        EvidenceContextFormatter.FormattedEvidence evidence = EvidenceContextFormatter.format(
                fused, scope.knowledgeBases(), attemptsExecuted, properties.getMaxEvidenceItems());
        String content = evidence.content();

        if (!highQuality) {
            log.warn("[ProductRAG] ⚠️ 检索质量低: query={}, qualityScore={}, threshold={}, 耗时={}ms",
                    query, String.format("%.4f", qualityScore),
                    ctx.getQualityThreshold(), ctx.getElapsedMs());

            return RetrievalQualityResult.insufficientEvidence(
                    content, qualityScore,
                    "知识库中未找到与「" + query + "」相关的足够依据。");
        }

        int activePaths = (int) ctx.getPathResults().values()
                .stream().filter(p -> !p.isEmpty()).count();
        log.info("[ProductRAG] ✅ 检索完成: query={}, qualityScore={}, activePaths={}, fused={}, 耗时={}ms",
                query, String.format("%.4f", qualityScore),
                activePaths, fused.size(), ctx.getElapsedMs());

        return RetrievalQualityResult.highQuality(content, qualityScore);
    }

    private RagSearchContext executeAttempt(String retrievalQuery,
                                            String originalUserQuery,
                                            KnowledgeScopeSelector.KnowledgeScope scope,
                                            int attemptNo) {
        RagSearchContext context = new RagSearchContext(retrievalQuery);
        context.setQualityThreshold(0.30);
        context.setAttribute("rag.originalUserQuery", originalUserQuery);
        context.setAttribute("rag.knowledgeBases", scope.knowledgeBases());
        context.setAttribute("rag.scopeReason", scope.reason());
        context.setAttribute("rag.attempt", attemptNo);
        return pipeline.execute(context);
    }

    private boolean hasSufficientEvidence(RagSearchContext context) {
        return context != null
                && context.getFusedResults() != null
                && !context.getFusedResults().isEmpty()
                && context.getQualityScore() >= properties.getSufficientScore();
    }

    private static boolean isBetter(RagSearchContext candidate, RagSearchContext current) {
        boolean candidateHasEvidence = candidate != null && !candidate.getFusedResults().isEmpty();
        boolean currentHasEvidence = current != null && !current.getFusedResults().isEmpty();
        if (candidateHasEvidence != currentHasEvidence) return candidateHasEvidence;
        if (candidate == null) return false;
        if (current == null) return true;
        int scoreCompare = Double.compare(candidate.getQualityScore(), current.getQualityScore());
        if (scoreCompare != 0) return scoreCompare > 0;
        return candidate.getFusedResults().size() > current.getFusedResults().size();
    }

    private static String evidenceSummary(RagSearchContext context) {
        if (context == null || context.getFusedResults().isEmpty()) return "首轮未召回任何证据";
        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < Math.min(3, context.getFusedResults().size()); i++) {
            String content = context.getFusedResults().get(i).getContent();
            if (content != null && !content.isBlank()) {
                summaries.add(content.length() > 300 ? content.substring(0, 300) : content);
            }
        }
        return String.join("\n", summaries);
    }

    private record Attempt(String query, RagSearchContext context) {
    }

    /**
     * 重建 BM25 索引（委托给 Bm25SearchHandler 的缓存重建逻辑）。
     */
    public void rebuildProductCache() {
        log.info("[ProductRAG] BM25 缓存由 Bm25SearchHandler 自动管理，无需手动重建");
    }
}
