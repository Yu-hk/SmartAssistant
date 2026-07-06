/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.pipeline.RagSearchPipeline;
import com.example.smartassistant.spi.ProductBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ⭐ 商品多路 RAG 检索服务。
 * <p>
 * 已重构为 Pipeline 模式，检索逻辑委派给 {@link RagSearchPipeline}。
 * 保留 {@link #retrieve(String)} 和 {@link #retrieveWithQuality(String)} 兼容旧调用。
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
    private final ProductBackend productBackend;

    public ProductRagService(RagSearchPipeline pipeline,
                             ProductBackend productBackend) {
        this.pipeline = pipeline;
        this.productBackend = productBackend;
    }

    /**
     * 多路 RAG 检索 + RRF 融合（兼容旧调用，无质量评估）。
     *
     * <p>新代码请使用 {@link #retrieveWithQuality(String)} 以获取质量分数和兜底提示。</p>
     *
     * @param query 用户查询文本
     * @return 融合后的检索结果字符串（质量低时可能返回空字符串）
     */
    public String retrieve(String query) {
        RetrievalResult result = retrieveWithQuality(query);
        return result.highQuality() ? result.content() : "";
    }

    /**
     * P1 RAG 质量评分结果。
     *
     * @param content       检索到的上下文文本
     * @param qualityScore  质量分数（0.0~1.0，RRF 分数归一化）
     * @param highQuality   是否高于质量阈值
     * @param fallback      兜底提示（当 highQuality=false 时使用）
     */
    public record RetrievalResult(
            String content,
            double qualityScore,
            boolean highQuality,
            String fallback
    ) {}

    /**
     * 带质量评估的 RAG 检索——委派给 {@link RagSearchPipeline}。
     *
     * @param query 用户查询
     * @return 检索结果（含质量分数和兜底提示）
     */
    public RetrievalResult retrieveWithQuality(String query) {
        if (query == null || query.isBlank()) {
            return new RetrievalResult("",
                    0.0, false,
                    "INSUFFICIENT_EVIDENCE: 请提供商品查询关键词，例如：iPhone 15 Pro 价格");
        }

        // 执行 Pipeline
        RagSearchContext ctx = new RagSearchContext(query);
        ctx.setQualityThreshold(0.30);
        ctx = pipeline.execute(ctx);

        // 从 Pipeline 结果构建返回
        List<RagSearchContext.RankedItem> fused = ctx.getFusedResults();
        double qualityScore = ctx.getQualityScore();

        if (fused.isEmpty()) {
            log.warn("[ProductRAG] ⚠️ Pipeline 全部路径未召回: query={}, 耗时={}ms",
                    query, ctx.getElapsedMs());
            return new RetrievalResult("",
                    0.0, false,
                    "INSUFFICIENT_EVIDENCE: 知识库中未找到与 '" + query + "' 相关的商品信息，请尝试更换关键词或联系人工客服。");
        }

        boolean highQuality = qualityScore >= ctx.getQualityThreshold();

        // 格式化输出（与旧版 retainWithQuality 相同格式）
        StringBuilder sb = new StringBuilder("【商品检索结果】\n");
        int rank = 1;
        for (RagSearchContext.RankedItem item : fused) {
            sb.append(rank).append(". ").append(item.getContent()).append("\n");
            rank++;
        }
        String content = sb.toString().trim();

        if (!highQuality) {
            log.warn("[ProductRAG] ⚠️ 检索质量低: query={}, qualityScore={}, threshold={}, 耗时={}ms",
                    query, String.format("%.4f", qualityScore),
                    ctx.getQualityThreshold(), ctx.getElapsedMs());
        } else {
            int activePaths = (int) ctx.getPathResults().values()
                    .stream().filter(p -> !p.isEmpty()).count();
            log.info("[ProductRAG] ✅ 检索完成: query={}, qualityScore={}, activePaths={}, fused={}, 耗时={}ms",
                    query, String.format("%.4f", qualityScore),
                    activePaths, fused.size(), ctx.getElapsedMs());
        }

        String fallback = highQuality ? null :
                "INSUFFICIENT_EVIDENCE: 检索结果质量较低（qualityScore="
                        + String.format("%.2f", qualityScore)
                        + "），知识库中未找到足够依据支持准确回答。请核对问题或联系人工客服。";
        return new RetrievalResult(content, qualityScore, highQuality, fallback);
    }

    /**
     * 重建 BM25 索引（委托给 Bm25SearchHandler 的缓存重建逻辑）。
     */
    public void rebuildProductCache() {
        // 通过 pipeline 找到 Bm25SearchHandler 触发重建
        // 但 Bm25SearchHandler 内部自动懒加载，外部不需要手动触发
        log.info("[ProductRAG] BM25 缓存由 Bm25SearchHandler 自动管理，无需手动重建");
    }
}
