/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.rag;

import com.example.smartassistant.entity.TravelNoteChunk;
import com.example.smartassistant.mapper.TravelNoteChunkMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 多路召回服务
 * <p>
 * 实现完整的 RAG 召回管道：Multi-Query 查询改写 → 多路并行检索 → RRF 融合 → 重排序 → Top-K。
 * <p>
 * 召回路径：
 * <ol>
 *   <li><b>向量检索</b>：pgvector cosine 相似度（主路径）</li>
 *   <li><b>全文检索</b>：PostgreSQL tsvector @@ plainto_tsquery（关键词精确匹配）</li>
 *   <li><b>地点关键词</b>：location_keywords LIKE（降级兜底）</li>
 * </ol>
 */
@Slf4j
@Service
public class RecallService {

    private final TravelNoteChunkMapper chunkMapper;
    private final EmbeddingService embeddingService;
    private final ChatClient chatClient;

    @Value("${travel.rag.top-k:5}")
    private int topK;

    @Value("${travel.rag.similarity-threshold:0.45}")
    private double similarityThreshold;

    @Value("${travel.rag.rrf-k:60}")
    private int rrfK;

    /** 每条召回路径贡献的候选数（= topK * multiplier） */
    private static final int PATH_CANDIDATE_MULTIPLIER = 3;

    /** Multi-Query 生成的查询变体数（不含原查询） */
    private static final int MULTI_QUERY_COUNT = 3;

    public RecallService(TravelNoteChunkMapper chunkMapper, EmbeddingService embeddingService,
                         @Qualifier("deepSeekChatModel") ChatModel chatModel) {
        this.chunkMapper = chunkMapper;
        this.embeddingService = embeddingService;
        this.chatClient = ChatClient.create(chatModel);
    }

    // ==================== 对外接口 ====================

    /**
     * 多路召回：Multi-Query → 多路检索 → RRF 融合 → Top-K
     *
     * @param location 地点（如"北京"）
     * @param query    用户查询（如"有什么好玩的"）
     * @param userId   用户 ID
     * @return 排序后的 Top-K 分块
     */
    public List<TravelNoteChunk> retrieve(String location, String query) {
        long start = System.currentTimeMillis();

        // 1. Multi-Query 查询改写：生成多个查询变体
        List<String> searchQueries = generateSearchQueries(location, query);
        log.info("[Recall] Multi-Query: 原始='{}', 变体={}", buildSearchText(location, query), searchQueries);

        // 2. 对每个查询变体执行多路召回
        int candidatesPerPath = topK * PATH_CANDIDATE_MULTIPLIER;
        List<RecallPathResult> allPaths = new ArrayList<>();

        for (String searchText : searchQueries) {
            allPaths.addAll(retrieveSingleQuery(searchText, location, candidatesPerPath));
        }

        if (allPaths.isEmpty()) {
            log.info("[Recall] 所有查询和路径均无结果, location={}, query={}", location, query);
            return List.of();
        }

        // 3. RRF 融合（所有查询变体、所有检索路径的结果一起融合）
        List<RankedChunk> fused = rrfFuse(allPaths);

        // 4. BGE 语义重排序（替代纯 RRF）+ Lost in the Middle 优化
        String searchText = buildSearchText(location, query);
        List<TravelNoteChunk> finalResults = rerank(fused, topK, searchText);

        log.info("[Recall] 完成: location={}, query={}, 查询变体={}, 召回路径={}, 最终结果={}, 耗时={}ms",
                location, query, searchQueries.size(), allPaths.size(), finalResults.size(),
                System.currentTimeMillis() - start);

        return finalResults;
    }

    // ==================== Multi-Query 生成 ====================

    /**
     * 生成多个搜索查询变体
     * <p>
     * 使用 LLM 从原始查询生成不同角度的搜索词，覆盖语义、关键词、具体细节等维度。
     * 包含原始查询本身，保证召回不降级。
     */
    private List<String> generateSearchQueries(String location, String query) {
        String original = buildSearchText(location, query);
        List<String> queries = new ArrayList<>();
        queries.add(original); // 始终保持原始查询

        if (original.isBlank()) {
            return queries;
        }

        try {
            String prompt = String.format("""
                    你是一个搜索查询改写专家。请将用户的问题改写成%d个不同的搜索查询变体，用于从游记数据库中检索相关信息。
                    
                    要求：
                    - 每个变体从不同角度表达用户的意图（语义、关键词、具体细节）
                    - 使用简洁的搜索词风格，不要完整的句子
                    - 每个查询一行，不要编号
                    - 只输出查询内容，不要多余解释
                    
                    原始问题：%s
                    """, MULTI_QUERY_COUNT, original);

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response != null && !response.isBlank()) {
                // 按行解析，过滤空行和明显非查询的行
                List<String> generated = Arrays.stream(response.split("\n"))
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .filter(line -> !line.startsWith("#"))
                        .filter(line -> !line.startsWith("-"))
                        .filter(line -> !line.startsWith("```"))
                        .filter(line -> !line.toLowerCase().startsWith("这里"))
                        .filter(line -> !line.toLowerCase().startsWith("以下"))
                        .map(line -> line.replaceAll("^\\d+[.、]\\s*", "")) // 去掉编号
                        .map(line -> line.replaceAll("^[*-]\\s*", ""))    // 去掉列表符号
                        .map(String::trim)
                        .filter(line -> line.length() > 3)
                        .limit(MULTI_QUERY_COUNT)
                        .toList();

                queries.addAll(generated);
                log.debug("[Recall] Multi-Query 生成: {} 个变体", generated.size());
            }
        } catch (Exception e) {
            log.warn("[Recall] Multi-Query 生成失败，使用原始查询: {}", e.getMessage());
        }

        return queries;
    }

    // ==================== 单查询检索 ====================

    /**
     * 对单个查询文本执行多路检索
     */
    private List<RecallPathResult> retrieveSingleQuery(String searchText, String location,
                                                        int candidatesPerPath) {
        List<RecallPathResult> paths = new ArrayList<>();

        // Path 1: 向量检索
        try {
            float[] queryVec = embeddingService.embed(searchText);
            List<TravelNoteChunk> results = chunkMapper.searchByEmbedding(
                    queryVec, location, candidatesPerPath);
            // 标记路径名包含查询摘要，便于调试
            String pathName = "vector:" + abbreviate(searchText);
            paths.add(new RecallPathResult(pathName, results));
        } catch (Exception e) {
            log.warn("[Recall] 向量检索失败: {}", e.getMessage());
        }

        // Path 2: 全文检索
        try {
            if (searchText != null && !searchText.isBlank()) {
                List<TravelNoteChunk> results = chunkMapper.searchByFullText(
                        searchText, location, candidatesPerPath);
                paths.add(new RecallPathResult("fulltext:" + abbreviate(searchText), results));
            }
        } catch (Exception e) {
            log.warn("[Recall] 全文检索失败: {}", e.getMessage());
        }

        return paths;
    }

    // ==================== 辅助方法 ====================

    private String buildSearchText(String location, String query) {
        if (location != null && !location.isBlank()) {
            if (query != null && !query.isBlank()) {
                return location + " " + query;
            }
            return location;
        }
        return query != null ? query : "";
    }

    /** 截断长文本用于日志 */
    private static String abbreviate(String text) {
        if (text == null) return "";
        return text.length() <= 20 ? text : text.substring(0, 20) + "...";
    }

    // ==================== RRF 融合 ====================

    /**
     * Reciprocal Rank Fusion 融合多路召回结果
     * <p>
     * 每个 chunk 的 RRF 分数 = Σ(1 / (rrfK + rank_i))，其中 rank_i 是该 chunk
     * 在第 i 条召回路径中的排序位置（从 1 开始）。
     * 同一 chunk 在不同路径中出现时分数累加。
     */
    private List<RankedChunk> rrfFuse(List<RecallPathResult> paths) {
        Map<Long, RankedChunk> fused = new LinkedHashMap<>();

        for (RecallPathResult path : paths) {
            List<TravelNoteChunk> chunks = path.getChunks();
            for (int i = 0; i < chunks.size(); i++) {
                TravelNoteChunk chunk = chunks.get(i);
                int rank = i + 1;
                double rrfScore = 1.0 / (rrfK + rank);

                RankedChunk existing = fused.get(chunk.getId());
                if (existing != null) {
                    existing.addScore(rrfScore);
                    existing.addSource(path.getPathName());
                } else {
                    RankedChunk rc = new RankedChunk(chunk, rrfScore);
                    rc.addSource(path.getPathName());
                    fused.put(chunk.getId(), rc);
                }
            }
        }

        return fused.values().stream()
                .sorted((a, b) -> Double.compare(b.getRrfScore(), a.getRrfScore()))
                .collect(Collectors.toList());
    }

    // ==================== 重排序 ====================

    /**
     * BGE 语义重排序 + Lost in the Middle 优化。
     * <p>
     * 1. 候选池粗过滤（RRF 阈值）
     * 2. 计算 query 与每个 chunk 的 BGE 余弦相似度
     * 3. 综合评分 = RRF×0.3 + BGE×0.7
     * 4. 按综合分排序取 Top-K
     * 5. Lost in the Middle: 首尾放置最相关文档，中间放次相关
     */
    private List<TravelNoteChunk> rerank(List<RankedChunk> ranked, int k, String queryText) {
        // 粗过滤
        List<RankedChunk> candidates = ranked.stream()
                .filter(rc -> rc.getRrfScore() >= minRrfScore())
                .collect(Collectors.toList());
        if (candidates.isEmpty()) return Collections.emptyList();

        // BGE query embedding
        float[] queryVec = embeddingService.embed(queryText);

        // BGE 重打分
        for (RankedChunk rc : candidates) {
            String chunkText = rc.getChunk().getChunkText();
            float[] chunkVec = chunkText != null ? embeddingService.embed(chunkText) : null;
            double bgeSim = (queryVec != null && chunkVec != null)
                    ? cosineSimilarity(queryVec, chunkVec) : 0;
            rc.setBgeScore(bgeSim);
        }

        // 综合评分排序：RRF×0.3 + BGE×0.7
        candidates.sort((a, b) -> Double.compare(
                b.getRrfScore() * 0.3 + b.getBgeScore() * 0.7,
                a.getRrfScore() * 0.3 + a.getBgeScore() * 0.7));

        // Lost in the Middle: 首尾放置最高分文档
        List<RankedChunk> reordered = lostInTheMiddle(candidates, k);
        if (reordered.size() > k) reordered = reordered.subList(0, k);

        return reordered.stream()
                .peek(rc -> rc.getChunk().setSimilarity(rc.getRrfScore()))
                .map(RankedChunk::getChunk)
                .collect(Collectors.toList());
    }

    /**
     * Lost in the Middle 优化：将最相关的文档放在 prompt 首尾位置。
     * 大模型对中间位置的文档关注度最低 (Liu et al. 2023)。
     */
    private List<RankedChunk> lostInTheMiddle(List<RankedChunk> ranked, int k) {
        List<RankedChunk> result = new ArrayList<>(k);
        int n = Math.min(ranked.size(), k);
        if (n <= 2) return ranked.subList(0, n);

        // 首尾交替放置：最高分放开头，次高分放结尾，第三高分放开头第二个...
        int left = 0, right = n - 1;
        boolean head = true;
        for (int i = 0; i < n && left <= right; i++) {
            if (head) {
                result.add(ranked.get(left++));
            } else {
                result.add(ranked.get(right--));
            }
            head = !head;
        }
        return result;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, nA = 0, nB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            nA += (double) a[i] * a[i];
            nB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(nA) * Math.sqrt(nB);
        return denom == 0 ? 0 : dot / denom;
    }

    private double minRrfScore() {
        return 1.0 / (rrfK + topK * PATH_CANDIDATE_MULTIPLIER);
    }

    // ==================== 内部类型 ====================

    @Getter
    @AllArgsConstructor
    @ToString
    private static class RecallPathResult {
        String pathName;
        List<TravelNoteChunk> chunks;
    }

    /**
     * 带 RRF 分数的分块
     */
    private static class RankedChunk {
        private final TravelNoteChunk chunk;
        private double rrfScore;
        private double bgeScore;
        private final List<String> sources = new ArrayList<>();

        RankedChunk(TravelNoteChunk chunk, double rrfScore) {
            this.chunk = chunk;
            this.rrfScore = rrfScore;
        }

        void addScore(double score) { this.rrfScore += score; }
        void addSource(String source) { this.sources.add(source); }
        void setBgeScore(double score) { this.bgeScore = score; }

        TravelNoteChunk getChunk() { return chunk; }
        double getRrfScore() { return rrfScore; }
        double getBgeScore() { return bgeScore; }
        List<String> getSources() { return sources; }
    }
}
