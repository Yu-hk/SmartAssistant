package com.example.smartassistant.service;

import com.example.smartassistant.entity.TravelNoteChunk;
import com.example.smartassistant.mapper.TravelNoteChunkMapper;
import com.example.smartassistant.mapper.TravelNoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Travel RAG 服务
 * 基于用户游记的检索增强生成
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TravelRagService {

    private final TravelNoteChunkMapper travelNoteChunkMapper;
    private final TravelNoteMapper travelNoteMapper;
    private final EmbeddingService embeddingService;

    @Value("${travel.rag.enabled:false}")
    private boolean ragEnabled;

    @Value("${travel.rag.top-k:3}")
    private int topK;

    @Value("${travel.rag.similarity-threshold:0.5}")
    private double similarityThreshold;

    /**
     * 判断是否需要 RAG 增强
     * 当问题涉及具体地点时触发
     */
    public boolean needsRagEnhancement(String location, Long userId) {
        if (!ragEnabled) {
            return false;
        }
        if (location == null || location.isEmpty()) {
            return false;
        }
        // 检查用户是否有相关游记
        return !travelNoteMapper.selectByLocationKeywords(location, userId).isEmpty();
    }

    /**
     * 检索相关攻略片段
     */
    public List<TravelNoteChunk> retrieveRelevantChunks(String location, String query, Long userId) {
        if (!ragEnabled || location == null) {
            return List.of();
        }

        try {
            // 1. 生成查询向量
            String searchText = location + " " + query;
            float[] queryEmbedding = embeddingService.embed(searchText);

            // 2. 向量相似度检索
            List<TravelNoteChunk> chunks = travelNoteChunkMapper.searchByEmbedding(
                    queryEmbedding, location, userId, topK);

            // 3. 过滤低相似度结果
            chunks.removeIf(chunk ->
                    chunk.getSimilarity() != null && chunk.getSimilarity() < similarityThreshold);

            log.info("[TravelRag] 检索结果: location={}, query={}, userId={}, chunks={}",
                    location, query, userId, chunks.size());

            return chunks;
        } catch (Exception e) {
            log.warn("[TravelRag] 检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 检索相关攻略片段（仅使用地点，不生成向量）
     * 适用于 Embedding 服务不可用时的降级方案
     */
    public List<TravelNoteChunk> retrieveByLocation(String location, Long userId) {
        if (!ragEnabled || location == null) {
            return List.of();
        }

        try {
            List<TravelNoteChunk> chunks = travelNoteChunkMapper.searchByLocation(
                    location, userId, topK);

            log.info("[TravelRag] 地点检索结果: location={}, userId={}, chunks={}",
                    location, userId, chunks.size());

            return chunks;
        } catch (Exception e) {
            log.warn("[TravelRag] 地点检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 构建增强上下文
     */
    public String buildRagContext(String location, List<TravelNoteChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("\n\n=== 用户历史攻略参考 ===\n");

        for (int i = 0; i < chunks.size(); i++) {
            TravelNoteChunk chunk = chunks.get(i);
            context.append(String.format("\n【攻略 %d】%s\n", i + 1,
                    chunk.getNoteTitle() != null ? chunk.getNoteTitle() : "未命名"));
            context.append(chunk.getChunkText());
            if (chunk.getSimilarity() != null) {
                context.append(String.format("\n(相关度: %.2f)", chunk.getSimilarity()));
            }
            context.append("\n");
        }

        context.append("=========================\n");
        return context.toString();
    }

    /**
     * 生成增强 Prompt
     */
    public String enhancePrompt(String originalPrompt, String location, String query, Long userId) {
        List<TravelNoteChunk> chunks = retrieveRelevantChunks(location, query, userId);

        if (chunks.isEmpty()) {
            // 降级：尝试仅使用地点检索
            chunks = retrieveByLocation(location, userId);
        }

        String ragContext = buildRagContext(location, chunks);

        if (ragContext.isEmpty()) {
            return originalPrompt;
        }

        return originalPrompt + ragContext +
                "\n请结合上述用户历史攻略，生成更个性化的出行建议和安排。";
    }
}
