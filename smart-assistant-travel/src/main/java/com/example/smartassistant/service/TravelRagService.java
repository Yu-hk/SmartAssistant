package com.example.smartassistant.service;

import com.example.smartassistant.entity.TravelNoteChunk;
import com.example.smartassistant.mapper.TravelNoteChunkMapper;
import com.example.smartassistant.mapper.TravelNoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    // ⭐ 美食判定：两级信号 + 积分制，减少误判
    // 强信号：直接指向美食内容，命中1个即判定
    private static final Set<String> FOOD_STRONG = Set.of(
            "美食", "火锅", "烧烤", "烤鱼", "小吃",
            "好吃", "正宗", "麻辣", "香辣", "入味",
            "小龙虾", "烤鸭", "炸酱面", "酸菜鱼", "水煮鱼",
            "川菜", "粤菜", "湘菜", "鲁菜",
            "簋街", "美食街", "夜宵", "大排档",
            "钵钵鸡", "兔头", "小龙坎", "全聚德",
            "豆汁", "焦圈", "糌粑", "酥油茶");

    // 弱信号：可能与美食相关，需累计 ≥2 个才判定
    private static final Set<String> FOOD_WEAK = Set.of(
            "吃", "餐厅", "点菜", "菜单", "排队",
            "味道", "人均", "价位", "口感");

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
     * 构建增强上下文（美食与旅行内容分离）
     * 旅行内容放入正文，美食内容作为建议
     */
    public String buildRagContext(String location, List<TravelNoteChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        List<TravelNoteChunk> travelChunks = new ArrayList<>();
        List<TravelNoteChunk> foodChunks = new ArrayList<>();

        for (TravelNoteChunk chunk : chunks) {
            if (isFoodRelated(chunk.getChunkText())) {
                foodChunks.add(chunk);
            } else {
                travelChunks.add(chunk);
            }
        }

        StringBuilder context = new StringBuilder();
        context.append("\n\n=== 用户历史攻略参考 ===\n");

        // 正文：旅行相关内容
        if (!travelChunks.isEmpty()) {
            for (int i = 0; i < travelChunks.size(); i++) {
                TravelNoteChunk chunk = travelChunks.get(i);
                context.append(String.format("\n【攻略 %d】%s\n", i + 1,
                        chunk.getNoteTitle() != null ? chunk.getNoteTitle() : "未命名"));
                context.append(chunk.getChunkText());
                if (chunk.getSimilarity() != null) {
                    context.append(String.format("\n(相关度: %.2f)", chunk.getSimilarity()));
                }
                context.append("\n");
            }
        }

        context.append("=========================\n");

        // 建议：美食相关内容
        if (!foodChunks.isEmpty()) {
            context.append("\n💡 美食建议（你的游记中提到过以下美食，可作为参考）：\n");
            for (int i = 0; i < foodChunks.size(); i++) {
                TravelNoteChunk chunk = foodChunks.get(i);
                context.append(String.format("  • %s\n", chunk.getChunkText().trim()));
            }
            context.append("如果用户想进一步了解美食信息，可以引导用户咨询美食服务。\n");
            context.append("=========================\n");
        }

        return context.toString();
    }

    /**
     * 判断 chunk 文本是否与美食相关（两级信号 + 积分制）
     * 强信号命中 1 个 → 判定为美食
     * 弱信号需累计 ≥ 2 个 → 判定为美食
     * 其余 → 判定为非美食（景点、行程、住宿等）
     */
    private boolean isFoodRelated(String text) {
        if (text == null || text.isEmpty()) return false;

        long strong = FOOD_STRONG.stream()
                .filter(kw -> text.contains(kw))
                .count();
        if (strong >= 1) return true;

        long weak = FOOD_WEAK.stream()
                .filter(kw -> text.contains(kw))
                .count();
        return weak >= 2;
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
