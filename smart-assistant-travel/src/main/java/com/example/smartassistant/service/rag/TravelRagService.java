package com.example.smartassistant.service.rag;

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
 * <p>
 * 基于用户游记的检索增强生成。
 * 核心检索委托给 {@link RecallService} 实现多路召回，
 * 本服务负责 RAG 开关控制、上下文构建和 Prompt 增强。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TravelRagService {

    private final TravelNoteChunkMapper travelNoteChunkMapper;
    private final TravelNoteMapper travelNoteMapper;
    private final RecallService recallService;

    @Value("${travel.rag.enabled:false}")
    private boolean ragEnabled;

    @Value("${travel.rag.top-k:3}")
    private int topK;

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
        return !travelNoteMapper.selectByLocationKeywords(location, userId).isEmpty();
    }

    /**
     * 多路召回相关攻略片段
     * <p>
     * 委托给 {@link RecallService#retrieve(String, String, Long)}，
     * 实现向量 + 全文 + 关键词多路召回 + RRF 融合。
     * 替代原有的单路向量检索。
     */
    public List<TravelNoteChunk> retrieveRelevantChunks(String location, String query, Long userId) {
        if (!ragEnabled || location == null) {
            return List.of();
        }
        return recallService.retrieve(location, query, userId);
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
     * 按内容类型检索攻略片段（纯文本查询，无需向量）
     * 用于获取美食建议等辅助内容
     */
    public List<TravelNoteChunk> retrieveByContentType(String location, Long userId, String contentType, int limit) {
        if (!ragEnabled || location == null) {
            return List.of();
        }

        try {
            List<TravelNoteChunk> chunks = travelNoteChunkMapper.searchByLocationAndType(
                    location, userId, contentType, limit);

            log.info("[TravelRag] 类型检索: location={}, userId={}, type={}, chunks={}",
                    location, userId, contentType, chunks.size());

            return chunks;
        } catch (Exception e) {
            log.warn("[TravelRag] 类型检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 简写：获取美食建议
     */
    public List<TravelNoteChunk> retrieveFoodSuggestions(String location, Long userId) {
        return retrieveByContentType(location, userId, "food", 3);
    }

    /**
     * 构建增强上下文（按 contentType 分离正文和建议）
     * 旅行内容放入正文，美食内容作为建议
     */
    public String buildRagContext(String location, List<TravelNoteChunk> chunks,
                                   List<TravelNoteChunk> foodChunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("\n\n=== 用户历史攻略参考 ===\n");

        int idx = 0;
        for (TravelNoteChunk chunk : chunks) {
            idx++;
            context.append(String.format("\n【攻略 %d】%s\n", idx,
                    chunk.getNoteTitle() != null ? chunk.getNoteTitle() : "未命名"));
            context.append(chunk.getChunkText());
            if (chunk.getSimilarity() != null) {
                context.append(String.format("\n(相关度: %.2f)", chunk.getSimilarity()));
            }
            context.append("\n");
        }

        context.append("=========================\n");

        if (foodChunks != null && !foodChunks.isEmpty()) {
            context.append("\n💡 美食建议（你的游记中提到过以下美食，可作为参考）：\n");
            for (int i = 0; i < foodChunks.size(); i++) {
                context.append(String.format("  • %s\n", foodChunks.get(i).getChunkText().trim()));
            }
            context.append("如果用户想进一步了解美食信息，可以引导用户咨询美食服务。\n");
            context.append("=========================\n");
        }

        return context.toString();
    }

    /**
     * 生成增强 Prompt（兼容旧接口，使用 contentType 过滤）
     */
    public String enhancePrompt(String originalPrompt, String location, String query, Long userId) {
        List<TravelNoteChunk> chunks = retrieveRelevantChunks(location, query, userId);

        if (chunks.isEmpty()) {
            chunks = retrieveByLocation(location, userId);
        }

        // 从检索结果中筛选非美食内容作为正文
        List<TravelNoteChunk> travelChunks = chunks.stream()
                .filter(c -> !"food".equals(c.getContentType()))
                .toList();

        // 单独查询美食建议
        List<TravelNoteChunk> foodChunks = retrieveFoodSuggestions(location, userId);

        String ragContext = buildRagContext(location, travelChunks, foodChunks);

        if (ragContext.isEmpty()) {
            return originalPrompt;
        }

        return originalPrompt + ragContext +
                "\n请结合上述用户历史攻略，生成更个性化的出行建议和安排。";
    }
}
