/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search;

import com.example.smartassistant.service.graph.ProductGraphService;
import com.example.smartassistant.spi.ProductBackend;
import com.example.smartassistant.tools.KnowledgeQueryTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ⭐ 商品多路 RAG 检索服务。
 * <p>
 * 实现多路召回 + RRF 融合管道：
 * <ol>
 *   <li><b>Path 1（精确匹配）</b>：ProductBackend.queryProductInfo() — 按商品编码精确查询</li>
 *   <li><b>Path 2（关键词搜索）</b>：ProductBackend.searchProduct() — 关键词模糊匹配</li>
 *   <li><b>Path 3（BM25 语义评分）</b>：Bm25Scorer — 对所有产品数据做 BM25 排序</li>
 *   <li><b>Path 4（经验知识）</b>：KnowledgeQueryTool — 历史经验知识库检索</li>
 * </ol>
 * 所有路径结果通过 RRF 融合后输出 Top-K 结果。
 * </p>
 */
@Service
public class ProductRagService {

    private static final Logger log = LoggerFactory.getLogger(ProductRagService.class);

    /** RRF 融合常数 */
    private static final int RRF_K = 60;

    /** Top-K 结果数 */
    private static final int TOP_K = 5;

    /** 质量分数归一化上限（RRF 理论最大值约为 4 paths × 1/RRF_K） */
    private static final double RRF_MAX = 4.0 / (RRF_K + 1);

    @Value("${product.rag.quality-threshold:0.30}")
    private double qualityThreshold;

    private final ProductBackend productBackend;
    private final Bm25Scorer bm25Scorer;
    private final KnowledgeQueryTool knowledgeQueryTool;
    /** P3: 商品知识图谱 */
    private final ProductGraphService productGraphService;

    /** 产品数据缓存（用于 BM25 评分） */
    private Map<String, String> productTextCache;

    public ProductRagService(ProductBackend productBackend,
                             Bm25Scorer bm25Scorer,
                             KnowledgeQueryTool knowledgeQueryTool,
                             ProductGraphService productGraphService) {
        this.productBackend = productBackend;
        this.bm25Scorer = bm25Scorer;
        this.knowledgeQueryTool = knowledgeQueryTool;
        this.productGraphService = productGraphService;
        this.productTextCache = new ConcurrentHashMap<>();
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
        // 质量低时返回空字符串（旧行为），但记录日志已在 retrieveWithQuality 中输出
        return result.highQuality() ? result.content() : "";
    }

    // ==================== 各检索路径 ====================

    /** Path 1: 精确匹配（按 productCode） */
    private RetrievalPathResult retrievePath1(String query) {
        List<String> results = new ArrayList<>();
        try {
            // 尝试直接作为 productCode 查询
            String info = productBackend.queryProductInfo(query.trim().toUpperCase());
            if (info != null && !info.contains("PRODUCT_NOT_FOUND")) {
                results.add(info);
            }
            // 也尝试搜索
            String searchResult = productBackend.searchProduct(query);
            if (searchResult != null && !searchResult.contains("未找到")) {
                // 从搜索结果中解析出每条商品
                for (String line : searchResult.split("\n")) {
                    if (line.startsWith("·")) {
                        String name = line.replace("·", "").trim().split("—")[0].trim();
                        String detail = productBackend.queryProductInfo(name);
                        if (detail != null && !detail.contains("PRODUCT_NOT_FOUND")) {
                            results.add(detail);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ProductRAG] Path1 失败: {}", e.getMessage());
        }
        return new RetrievalPathResult("精确匹配", results);
    }

    /** Path 2: 关键词模糊搜索 */
    private RetrievalPathResult retrievePath2(String query) {
        List<String> results = new ArrayList<>();
        try {
            String result = productBackend.searchProduct(query);
            if (result != null && !result.contains("未找到")) {
                for (String line : result.split("\n")) {
                    if (line.startsWith("·")) {
                        results.add(line.substring(1).trim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ProductRAG] Path2 失败: {}", e.getMessage());
        }
        return new RetrievalPathResult("关键词搜索", results);
    }

    /** Path 3: BM25 评分 */
    private RetrievalPathResult retrievePath3(String query) {
        List<String> results = new ArrayList<>();
        try {
            // 构建产品文本映射（首次使用时初始化 BM25 文档集合）
            if (productTextCache.isEmpty()) {
                rebuildProductCache();
            }
            if (productTextCache.isEmpty()) return new RetrievalPathResult("BM25", results);

            // BM25 排序
            List<Map.Entry<String, Double>> ranked = bm25Scorer.rank(query, productTextCache);
            for (Map.Entry<String, Double> entry : ranked) {
                try {
                    String info = productBackend.queryProductInfo(entry.getKey());
                    if (info != null && !info.contains("PRODUCT_NOT_FOUND")) {
                        results.add(info);
                    }
                } catch (Exception ignored) {}
                if (results.size() >= TOP_K) break;
            }
        } catch (Exception e) {
            log.warn("[ProductRAG] Path3 失败: {}", e.getMessage());
        }
        return new RetrievalPathResult("BM25", results);
    }

    /** Path 4: 知识库经验检索 */
    private RetrievalPathResult retrievePath4(String query) {
        List<String> results = new ArrayList<>();
        try {
            String knowledge = knowledgeQueryTool.queryKnowledge(query);
                if (knowledge != null && !knowledge.isBlank()
                        && !knowledge.contains("未找到") && !knowledge.contains("PRODUCT_NOT_FOUND")) {
                    results.add(knowledge);
                }
        } catch (Exception e) {
            log.warn("[ProductRAG] Path4 失败: {}", e.getMessage());
        }
        return new RetrievalPathResult("知识库", results);
    }

    /**
     * ⭐ Path 5: Graph 图检索 — 商品关系图谱。
     * <p>P3 新增：从用户查询中提取商品编码，查询关系图谱获取同类/配件/替代品推荐。</p>
     */
    private RetrievalPathResult retrievePath5(String query) {
        List<String> results = new ArrayList<>();
        try {
            String matchedCode = productGraphService.matchProduct(query);
            if (matchedCode != null) {
                String name = productGraphService.getProductName(matchedCode);
                // 综合推荐（同类 + 配件 + 替代等）
                var recommendations = productGraphService.queryRecommendations(matchedCode, 3);
                if (!recommendations.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("【关联推荐】基于 ").append(name != null ? name : matchedCode).append("：\n");
                    int rank = 1;
                    for (var rec : recommendations) {
                        sb.append("  ").append(rank).append(". ")
                                .append(rec.getProductName())
                                .append(" (").append(rec.getRelationType().name()).append(")")
                                .append("\n");
                        rank++;
                    }
                    results.add(sb.toString().trim());
                    log.info("[ProductRAG] Path5 Graph检索命中: query={}, matchedCode={}, recommendations={}",
                            query, matchedCode, recommendations.size());
                }
            }
        } catch (Exception e) {
            log.warn("[ProductRAG] Path5 失败: {}", e.getMessage());
        }
        return new RetrievalPathResult("图谱检索", results);
    }

    /**
     * 从 ProductBackend 重建产品文本缓存（用于 BM25）。
     */
    public void rebuildProductCache() {
        productTextCache.clear();
        // 从 InMemoryProductBackend 获取所有产品数据构建索引
        // 通过搜索空字符串或默认产品列表
        String allProducts = productBackend.searchProduct("");
        if (allProducts != null && !allProducts.contains("未找到")) {
            for (String line : allProducts.split("\n")) {
                if (line.startsWith("·")) {
                    String name = line.replace("·", "").trim().split("—")[0].trim();
                    try {
                        String info = productBackend.queryProductInfo(name);
                        if (info != null) {
                            productTextCache.put(name, info);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // 初始化 BM25 文档集合
        if (!productTextCache.isEmpty()) {
            bm25Scorer.initialize(new ArrayList<>(productTextCache.values()));
            log.info("[ProductRAG] BM25 索引重建完成: {} 个产品", productTextCache.size());
        }
    }

    // ==================== RRF 融合 ====================

    /**
     * Reciprocal Rank Fusion 融合多路召回结果。
     * 每个 item 的 RRF 分数 = Σ 1/(RRF_K + rank_i)
     */
    private List<RankedItem> rrfFuse(List<RetrievalPathResult> paths) {
        Map<String, RankedItem> fused = new LinkedHashMap<>();

        for (RetrievalPathResult path : paths) {
            List<String> items = path.items;
            for (int i = 0; i < items.size(); i++) {
                String content = items.get(i);
                int rank = i + 1;
                double rrfScore = 1.0 / (RRF_K + rank);

                RankedItem existing = fused.get(content);
                if (existing != null) {
                    existing.addScore(rrfScore);
                } else {
                    RankedItem ri = new RankedItem(content, rrfScore);
                    fused.put(content, ri);
                }
            }
        }

        return fused.values().stream()
                .sorted((a, b) -> Double.compare(b.getRrfScore(), a.getRrfScore()))
                .limit(TOP_K)
                .collect(Collectors.toList());
    }

    // ==================== 内部类 ====================

    /** 单条检索路径的结果 */
    static class RetrievalPathResult {
        final String pathName;
        final List<String> items;

        RetrievalPathResult(String pathName, List<String> items) {
            this.pathName = pathName;
            this.items = items != null ? items : List.of();
        }
    }

    /** 带 RRF 分数的检索结果项 */
    static class RankedItem {
        final String content;
        private double rrfScore;

        RankedItem(String content, double rrfScore) {
            this.content = content;
            this.rrfScore = rrfScore;
        }

        void addScore(double score) { this.rrfScore += score; }
        double getRrfScore() { return rrfScore; }
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
     * P1 带质量评估的 RAG 检索。
     *
     * <p>在 RRF 融合后评估 Top-1 的 RRF 分数，低于阈值时触发降级兜底。</p>
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

        long start = System.currentTimeMillis();
        List<RetrievalPathResult> allPaths = new ArrayList<>();

        allPaths.add(retrievePath1(query));
        allPaths.add(retrievePath2(query));
        allPaths.add(retrievePath3(query));
        allPaths.add(retrievePath4(query));
        // ⭐ P3: Graph 检索 — 商品关系图谱（推荐同类/配件/替代品）
        allPaths.add(retrievePath5(query));

        // RRF 融合（返回带分数的列表）
        List<RankedItem> fused = rrfFuse(allPaths);
        int activePaths = (int) allPaths.stream().filter(p -> !p.items.isEmpty()).count();
        long elapsed = System.currentTimeMillis() - start;

        if (fused.isEmpty()) {
            log.warn("[ProductRAG] ⚠️ 全部路径未召回: query={}, activePaths={}, 耗时={}ms",
                    query, activePaths, elapsed);
            return new RetrievalResult("",
                    0.0, false,
                    "INSUFFICIENT_EVIDENCE: 知识库中未找到与 '" + query + "' 相关的商品信息,请尝试更换关键词或联系人工客服。");
        }

        // 质量分数 = Top-1 RRF 分数归一化到 0~1
        double topRrf = fused.get(0).getRrfScore();
        double qualityScore = Math.min(1.0, topRrf / RRF_MAX);
        boolean highQuality = qualityScore >= qualityThreshold;

        // 格式化输出
        StringBuilder sb = new StringBuilder("【商品检索结果】\n");
        int rank = 1;
        for (RankedItem item : fused) {
            sb.append(rank).append(". ").append(item.content).append("\n");
            rank++;
        }
        String content = sb.toString().trim();

        if (!highQuality) {
            log.warn("[ProductRAG] ⚠️ 检索质量低: query={}, qualityScore={}, threshold={}, topRrf={}, 耗时={}ms",
                    query, String.format("%.4f", qualityScore),
                    qualityThreshold, String.format("%.4f", topRrf), elapsed);
        } else {
            log.info("[ProductRAG] ✅ 检索完成: query={}, qualityScore={}, activePaths={}, fused={}, 耗时={}ms",
                    query, String.format("%.4f", qualityScore), activePaths, fused.size(), elapsed);
        }

        String fallback = highQuality ? null :
                "INSUFFICIENT_EVIDENCE: 检索结果质量较低（qualityScore="
                        + String.format("%.2f", qualityScore)
                        + "），知识库中未找到足够依据支持准确回答。请核对问题或联系人工客服。";
        return new RetrievalResult(content, qualityScore, highQuality, fallback);
    }
}
