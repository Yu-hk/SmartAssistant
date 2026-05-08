package com.example.smartassistant.service;

import com.example.smartassistant.entity.TravelNoteChunk;
import com.example.smartassistant.mapper.TravelNoteChunkMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 多路召回服务
 * <p>
 * 实现完整的 RAG 召回管道：查询改写 → 多路并行检索 → RRF 融合 → 重排序 → Top-K。
 * 替代原有的单路向量检索方式，提升召回准确率和覆盖率。
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
@RequiredArgsConstructor
public class RecallService {

    private final TravelNoteChunkMapper chunkMapper;
    private final EmbeddingService embeddingService;

    @Value("${travel.rag.top-k:5}")
    private int topK;

    @Value("${travel.rag.similarity-threshold:0.45}")
    private double similarityThreshold;

    @Value("${travel.rag.rrf-k:60}")
    private int rrfK;

    /** 每条召回路径贡献的候选数（= topK * multiplier） */
    private static final int PATH_CANDIDATE_MULTIPLIER = 3;

    // ==================== 对外接口 ====================

    /**
     * 多路召回：查询改写 → 多路并行检索 → RRF 融合 → 重排序 → Top-K
     *
     * @param location 地点（如"北京"）
     * @param query    用户查询（如"有什么好玩的"）
     * @param userId   用户 ID
     * @return 排序后的 Top-K 分块
     */
    public List<TravelNoteChunk> retrieve(String location, String query, Long userId) {
        long start = System.currentTimeMillis();

        // 1. 查询改写
        ExpandedQuery expanded = expandQuery(location, query);
        log.debug("[Recall] 查询改写: original='{}', expanded='{}'", query, expanded.getSearchText());

        // 2. 多路召回
        int candidatesPerPath = topK * PATH_CANDIDATE_MULTIPLIER;
        List<RecallPathResult> allPaths = new ArrayList<>();

        // Path 1: 向量检索（主路径）
        try {
            float[] queryVec = embeddingService.embed(expanded.getSearchText());
            List<TravelNoteChunk> vecResults = chunkMapper.searchByEmbedding(
                    queryVec, location, userId, candidatesPerPath);
            allPaths.add(new RecallPathResult("vector", vecResults));
            log.debug("[Recall] 向量检索: {} 条结果", vecResults.size());
        } catch (Exception e) {
            log.warn("[Recall] 向量检索失败: {}", e.getMessage());
        }

        // Path 2: 全文检索（tsvector）
        try {
            if (expanded.getSearchText() != null && !expanded.getSearchText().isBlank()) {
                List<TravelNoteChunk> ftResults = chunkMapper.searchByFullText(
                        expanded.getSearchText(), location, userId, candidatesPerPath);
                allPaths.add(new RecallPathResult("fulltext", ftResults));
                log.debug("[Recall] 全文检索: {} 条结果", ftResults.size());
            }
        } catch (Exception e) {
            log.warn("[Recall] 全文检索失败: {}", e.getMessage());
        }

        // Path 3: 地点关键词降级（仅当其他路径无结果时）
        boolean hasResults = allPaths.stream().anyMatch(p -> !p.getChunks().isEmpty());
        if (!hasResults && location != null && !location.isBlank()) {
            try {
                List<TravelNoteChunk> locResults = chunkMapper.searchByLocation(
                        location, userId, candidatesPerPath);
                allPaths.add(new RecallPathResult("keyword", locResults));
                log.debug("[Recall] 关键词降级: {} 条结果", locResults.size());
            } catch (Exception e) {
                log.warn("[Recall] 关键词检索失败: {}", e.getMessage());
            }
        }

        // 无结果
        if (allPaths.isEmpty()) {
            log.info("[Recall] 所有召回路径均无结果, location={}, query={}", location, query);
            return List.of();
        }

        // 3. RRF 融合
        List<RankedChunk> fused = rrfFuse(allPaths);

        // 4. 重排序 + 阈值过滤
        List<TravelNoteChunk> finalResults = rerank(fused, topK);

        log.info("[Recall] 完成: location={}, query={}, 召回路径={}, 最终结果={}, 耗时={}ms",
                location, query, allPaths.size(), finalResults.size(),
                System.currentTimeMillis() - start);

        return finalResults;
    }

    // ==================== 查询改写 ====================

    /**
     * 查询改写：将 location 和 query 拼接并做初步扩展
     */
    private ExpandedQuery expandQuery(String location, String query) {
        String searchText;
        if (location != null && !location.isBlank()) {
            if (query != null && !query.isBlank()) {
                searchText = location + " " + query;
            } else {
                searchText = location;
            }
        } else {
            searchText = query != null ? query : "";
        }
        return new ExpandedQuery(searchText);
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
        // chunkId → { totalRrfScore, chunk }
        Map<Long, RankedChunk> fused = new LinkedHashMap<>();

        for (RecallPathResult path : paths) {
            List<TravelNoteChunk> chunks = path.getChunks();
            for (int i = 0; i < chunks.size(); i++) {
                TravelNoteChunk chunk = chunks.get(i);
                int rank = i + 1;  // 排序位置从 1 开始
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

        // 按 RRF 分数降序排列
        return fused.values().stream()
                .sorted((a, b) -> Double.compare(b.getRrfScore(), a.getRrfScore()))
                .collect(Collectors.toList());
    }

    // ==================== 重排序 ====================

    /**
     * 重排序：按 RRF 分数降序取 Top-K，过滤低分结果
     */
    private List<TravelNoteChunk> rerank(List<RankedChunk> ranked, int k) {
        return ranked.stream()
                // 过滤低分：RRF 分数 < 阈值对应的参考值
                .filter(rc -> rc.getRrfScore() >= minRrfScore())
                .limit(k)
                .peek(rc -> {
                    // 将 RRF 分数映射到 similarity 字段（兼容上游消费代码）
                    TravelNoteChunk chunk = rc.getChunk();
                    chunk.setSimilarity(rc.getRrfScore());
                })
                .map(RankedChunk::getChunk)
                .collect(Collectors.toList());
    }

    /**
     * RRF 最低分数阈值（近似等价于 similarityThreshold）
     * 用于过滤极端低分结果
     */
    private double minRrfScore() {
        // 假设至少在某一路径中排进前 N 名
        // 当 k=60, rank=topK*PATH_CANDIDATE_MULTIPLIER 时：
        // minScore = 1/(60 + topK*3)
        return 1.0 / (rrfK + topK * PATH_CANDIDATE_MULTIPLIER);
    }

    // ==================== 内部类型 ====================

    @Getter
    @AllArgsConstructor
    @ToString
    private static class ExpandedQuery {
        String searchText;
    }

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
        private final List<String> sources = new ArrayList<>();

        RankedChunk(TravelNoteChunk chunk, double rrfScore) {
            this.chunk = chunk;
            this.rrfScore = rrfScore;
        }

        void addScore(double score) {
            this.rrfScore += score;
        }

        void addSource(String source) {
            this.sources.add(source);
        }

        TravelNoteChunk getChunk() { return chunk; }
        double getRrfScore() { return rrfScore; }
        List<String> getSources() { return sources; }
    }
}
