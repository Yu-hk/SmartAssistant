/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.rag;

import com.example.smartassistant.entity.TravelNote;
import com.example.smartassistant.entity.TravelNoteChunk;
import com.example.smartassistant.mapper.TravelNoteChunkMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 游记智能匹配服务
 *
 * <p>核心功能：通过规则评分从游记库中找出 TopN 篇最匹配的游记
 *
 * <p>流程：
 * <ol>
 *     <li><b>规则评分</b> - 使用多维度规则对游记进行评分</li>
 *     <li><b>TopN 筛选</b> - 返回评分最高的 N 篇游记</li>
 *     <li><b>降级策略</b> - 无游记时降级使用 RAG 向量检索</li>
 * </ol>
 *
 * <p>评分维度：
 * <ul>
 *     <li>新鲜度 (15%): 游记发布时间越近，价值越高</li>
 *     <li>意图匹配 (30%): 关键词/标签与用户当前意图的匹配程度</li>
 *     <li>偏好匹配 (15%): 与用户历史偏好的一致性</li>
 *     <li>内容质量 (20%): 内容长度、标签完整性等</li>
 *     <li>性价比 (20%): 景点价格与体验价值的平衡</li>
 * </ul>
 */
@Service
@Slf4j
public class TravelNoteMatchService {

    // ==================== 配置常量 ====================
    private static final int DEFAULT_TOP_N = 5;           // 默认返回 Top 5
    private static final String SEEN_KEY_PREFIX = "travel:seen:";  // Redis key 前缀
    private static final int SEEN_KEY_TTL_MINUTES = 30;            // TTL 30 分钟

    // ==================== 依赖服务 ====================
    private final TravelRagService travelRagService;
    private final TravelNoteRankingService rankingService;
    private final TravelNoteChunkMapper travelNoteChunkMapper;
    private final StringRedisTemplate redisTemplate;  // ⭐ Redis 不放回追踪

    // ==================== 配置 ====================
    @Value("${travel.rag.enabled:false}")
    private boolean ragEnabled;

    public TravelNoteMatchService(
            TravelNoteService travelNoteService,
            TravelRagService travelRagService,
            TravelNoteRankingService rankingService,
            TravelNoteChunkMapper travelNoteChunkMapper,
            StringRedisTemplate redisTemplate) {
        this.travelRagService = travelRagService;
        this.rankingService = rankingService;
        this.travelNoteChunkMapper = travelNoteChunkMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 根据用户意图，从指定地点的游记中找出最匹配的 TopN 篇
     *
     * @param userId     用户ID（可选，用于个性化推荐）
     * @param location   目的地/地点
     * @param userIntent 用户的当前意图（如"带娃游玩"、"美食"、"情侣出行"）
     * @return 匹配结果
     */
    public MatchResult selectBestTravelNote(Long userId, String location, String userIntent) {
        log.info("[TravelNoteMatch] 开始匹配: userId={}, location={}, intent={}",
                userId, location, userIntent);

        // 检查 RAG 功能是否启用
        if (!ragEnabled) {
            return MatchResult.disabled("攻略RAG功能未启用，请在配置中开启 travel.rag.enabled");
        }

        // 检查地点参数
        if (location == null || location.isEmpty()) {
            return MatchResult.error("地点不能为空");
        }

        try {
            // ========== 规则评分筛选 TopN ==========
            log.info("[TravelNoteMatch] ========== 规则评分筛选 ==========");

            TravelNoteRankingService.RankingResult rankingResult =
                    rankingService.rankTravelNotes(userId, location, userIntent);

            if (rankingResult.isEmpty()) {
                // 无游记时降级：尝试从 RAG 分块中检索
                log.info("[TravelNoteMatch] 该地点无游记，降级使用 RAG 向量检索");
                return fallbackToRagRetrieval(userId, location, userIntent);
            }

            // 获取 TopN 候选游记列表
            List<TravelNote> topNotes = rankingResult.getTopNotes(DEFAULT_TOP_N);
            log.info("[TravelNoteMatch] 规则评分筛选出 {} 篇候选游记", topNotes.size());

            // ⭐ 收集被筛掉的笔记 ID（排名 #6 及之后），提取碎片作为补充建议
            // 遵循"不放回"策略：同一 userId+location 已推荐过的笔记不再重复推荐
            // Redis key: travel:seen:{userId}:{location}, TTL 30 分钟
            List<String> tips = List.of();
            List<TravelNoteRankingService.ScoredNote> allScored = rankingResult.getRankedNotes();
            String seenKey = SEEN_KEY_PREFIX + userId + ":" + location.replaceAll("\\s+", "");

            if (allScored.size() > DEFAULT_TOP_N) {
                List<Long> remainingIds = allScored.stream()
                        .skip(DEFAULT_TOP_N)
                        .map(sn -> sn.getNote().getId())
                        .filter(id -> !isNoteSeen(seenKey, id))
                        .collect(Collectors.toList());
                log.info("[TravelNoteMatch] 剩余 {} 篇游记（已排除 {} 篇已推荐的），提取补充建议",
                        remainingIds.size(),
                        allScored.size() - DEFAULT_TOP_N - remainingIds.size());

                if (!remainingIds.isEmpty()) {
                    List<TravelNoteChunk> tipChunks = travelNoteChunkMapper.searchRandomByNoteIds(remainingIds, 3);
                    tips = tipChunks.stream()
                            .map(c -> {
                                String type = c.getContentType() != null ? c.getContentType() : "general";
                                String text = c.getChunkText().length() > 80
                                        ? c.getChunkText().substring(0, 80) + "..."
                                        : c.getChunkText();
                                return text + "（" + type + "）";
                            })
                            .collect(Collectors.toList());
                    tipChunks.stream().map(TravelNoteChunk::getNoteId).forEach(id -> markNoteSeen(seenKey, id));
                    log.info("[TravelNoteMatch] 提取到 {} 条补充建议，已标记为已读", tips.size());
                }
            }

            // 获取最佳推荐
            TravelNote bestNote = topNotes.get(0);
            List<TravelNote> alternatives = topNotes.stream().skip(1).collect(Collectors.toList());

            // ⭐ 标记本轮 bestNote 和 alternatives 为已读
            markNoteSeen(seenKey, bestNote.getId());
            topNotes.stream().skip(1).map(TravelNote::getId).forEach(id -> markNoteSeen(seenKey, id));

            return MatchResult.success(
                    bestNote,
                    rankingResult.toMcpText(),
                    userIntent,
                    alternatives,
                    tips
            );

        } catch (Exception e) {
            log.error("[TravelNoteMatch] 匹配失败: {}", e.getMessage(), e);
            return MatchResult.error("匹配失败: " + e.getMessage());
        }
    }

    // ==================== Redis 不放回追踪 ====================

    private boolean isNoteSeen(String key, Long noteId) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(key, String.valueOf(noteId)));
        } catch (Exception e) {
            log.warn("[TravelNoteMatch] Redis 查询失败(seen)，默认返回未读: {}", e.getMessage());
            return false;
        }
    }

    private void markNoteSeen(String key, Long noteId) {
        try {
            redisTemplate.opsForSet().add(key, String.valueOf(noteId));
            redisTemplate.expire(key, SEEN_KEY_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("[TravelNoteMatch] Redis 写入失败(seen): {}", e.getMessage());
        }
    }

    // ==================== 降级策略 ====================

    /**
     * 降级策略：无游记时用 RAG 向量检索
     */
    private MatchResult fallbackToRagRetrieval(Long userId, String location, String userIntent) {
        log.info("[TravelNoteMatch] 该地点无游记，降级使用 RAG 向量检索");

        var chunks = travelRagService.retrieveRelevantChunks(location, userIntent, userId);
        if (chunks.isEmpty()) {
            return MatchResult.empty(location);
        }

        StringBuilder aggregated = new StringBuilder();
        for (var chunk : chunks) {
            aggregated.append("【攻略片段】").append(chunk.getChunkText()).append("\n\n");
        }

        TravelNote.VirtualTravelNote virtual = new TravelNote.VirtualTravelNote(
                "【RAG 检索】" + location + " 相关攻略",
                location,
                aggregated.toString()
        );

        String reason = "根据 RAG 向量检索找到 " + chunks.size() + " 个相关片段，已聚合成参考内容。";
        return MatchResult.success(virtual, reason, userIntent, List.of(), List.of());
    }

    // ==================== MatchResult 内部类 ====================

    @Getter
    public static class MatchResult {
        // Getter
        private final boolean success;
        private final boolean enabled;
        private final String errorMessage;
        private final TravelNote bestNote;
        private final String reasoning;
        private final String userIntent;
        private final List<TravelNote> alternatives;
        private final List<String> tips;  // ⭐ 被筛游记提取的补充建议

        private MatchResult(boolean success, boolean enabled, String errorMessage,
                           TravelNote bestNote, String reasoning, String userIntent,
                           List<TravelNote> alternatives, List<String> tips) {
            this.success = success;
            this.enabled = enabled;
            this.errorMessage = errorMessage;
            this.bestNote = bestNote;
            this.reasoning = reasoning;
            this.userIntent = userIntent;
            this.alternatives = alternatives != null ? alternatives : List.of();
            this.tips = tips != null ? tips : List.of();
        }

        public static MatchResult success(TravelNote bestNote, String reasoning,
                                         String userIntent, List<TravelNote> alternatives,
                                         List<String> tips) {
            return new MatchResult(true, true, null, bestNote, reasoning, userIntent, alternatives, tips);
        }

        public static MatchResult disabled(String message) {
            return new MatchResult(false, false, message, null, null, null, null, null);
        }

        public static MatchResult error(String message) {
            return new MatchResult(false, true, message, null, null, null, null, null);
        }

        public static MatchResult empty(String location) {
            return new MatchResult(false, true,
                    "📝 暂无【" + location + "】相关的游记记录\n提示：用户可以通过上传游记来获得更个性化的推荐",
                    null, null, null, null, null);
        }

        public String toMcpText() {
            if (!enabled) {
                return "⚠️ " + errorMessage;
            }
            if (!success) {
                return "⚠️ 匹配失败: " + errorMessage;
            }
            if (bestNote == null) {
                return "📝 暂无相关游记，请上传游记以获得个性化推荐";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🗺️ 【个性化推荐结果】\n");
            sb.append("📍 目的地：").append(bestNote.getLocation() != null ? bestNote.getLocation() : "未知").append("\n");
            sb.append("🎯 匹配意图：").append(userIntent != null ? userIntent : "综合推荐").append("\n");
            sb.append("━".repeat(40)).append("\n\n");

            sb.append("✅ 【最佳推荐】\n");
            if (bestNote.getTitle() != null) {
                sb.append("📌 ").append(bestNote.getTitle()).append("\n");
            }
            sb.append(formatNoteContent(bestNote)).append("\n");

            if (reasoning != null && !reasoning.isEmpty()) {
                sb.append("\n💡 【推荐理由】\n");
                sb.append(reasoning);
                sb.append("\n");
            }

            if (alternatives != null && !alternatives.isEmpty()) {
                sb.append("\n📋 【其他备选】\n");
                for (int i = 0; i < alternatives.size(); i++) {
                    TravelNote alt = alternatives.get(i);
                    sb.append("  ").append(i + 1).append(". ");
                    sb.append(nullToEmpty(alt.getTitle()));
                    if (alt.getTags() != null) {
                        sb.append(" (").append(alt.getTags()).append(")");
                    }
                    sb.append("\n");
                }
            }

            // ⭐ 补充建议：被筛游记中的碎片内容
            if (tips != null && !tips.isEmpty()) {
                sb.append("\n💡 其他游记还提到：\n");
                for (String tip : tips) {
                    sb.append("  • ").append(tip).append("\n");
                }
            }

            return sb.toString();
        }

        private String formatNoteContent(TravelNote note) {
            if (note.getContent() == null) return "";
            String content = note.getContent();
            return content.length() > 500 ? content.substring(0, 500) + "..." : content;
        }

        private String nullToEmpty(String s) {
            return s != null ? s : "";
        }

    }
}
