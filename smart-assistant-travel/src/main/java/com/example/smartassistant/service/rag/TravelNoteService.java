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
import com.example.smartassistant.mapper.TravelNoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户游记/攻略服务
 * 负责攻略的 CRUD 和分块处理
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TravelNoteService {

    private final TravelNoteMapper travelNoteMapper;
    private final TravelNoteChunkMapper travelNoteChunkMapper;
    private final EmbeddingService embeddingService;
    private final SemanticChunker semanticChunker;

    // 最大内容长度（防止 OOM）
    private static final int MAX_CONTENT_LENGTH = 50000;

    // ⭐ 内容类型分类关键词集 ========================================

    // 美食 - 强信号（命中1个即判定）
    private static final Set<String> FOOD_STRONG = Set.of(
            "美食", "火锅", "烧烤", "烤鱼", "小吃",
            "好吃", "正宗", "麻辣", "香辣", "入味",
            "小龙虾", "烤鸭", "炸酱面", "酸菜鱼", "水煮鱼",
            "川菜", "粤菜", "湘菜", "鲁菜",
            "簋街", "美食街", "夜宵", "大排档",
            "钵钵鸡", "兔头", "小龙坎", "全聚德",
            "豆汁", "焦圈", "糌粑", "酥油茶");

    // 美食 - 弱信号（需累计≥2个才判定）
    private static final Set<String> FOOD_WEAK = Set.of(
            "吃", "餐厅", "点菜", "菜单", "排队",
            "味道", "人均", "价位", "口感");

    // 住宿
    private static final Set<String> ACCOMMODATION = Set.of(
            "住", "民宿", "酒店", "房间", "入住",
            "房东", "客栈", "青旅", "宾馆", "旅馆",
            "床", "洗手间", "浴室", "空调", "wifi");

    // 交通
    private static final Set<String> TRANSPORT = Set.of(
            "坐车", "打车", "公交", "地铁", "自驾",
            "开车", "停车", "步行", "走路", "骑车",
            "车程", "路程", "导航", "方向", "班车",
            "缆车", "索道", "船", "飞机", "高铁");

    // 景点/游玩
    private static final Set<String> SCENIC = Set.of(
            "景点", "门票", "开放", "参观", "游玩",
            "游览", "拍照", "打卡", "风景", "壮观",
            "建议", "推荐", "行程", "路线", "攻略",
            "博物院", "博物馆", "公园", "寺庙", "古镇", "湖", "山");

    /**
     * 创建游记
     * @param userId 用户 ID
     * @param title 标题
     * @param content 内容
     * @param location 主要目的地
     * @param tags 标签（逗号分隔字符串）
     * @return 创建的游记 ID
     */
    @Transactional
    public Long createNote(Long userId, String title, String content, String location, String tags) {
        TravelNote note = TravelNote.builder()
                .userId(userId)
                .title(title)
                .content(content)
                .sourceType("text")
                .location(location)
                .tags(tags)
                .status("active")
                .build();

        travelNoteMapper.insert(note);
        log.info("[TravelNote] 创建游记: id={}, userId={}, title={}", note.getId(), userId, title);

        // 自动分块
        chunkAndSaveContent(note.getId(), content, location, tags);

        return note.getId();
    }

    /**
     * 从外部 URL 创建游记（如爬取的攻略）
     * @param title 标题
     * @param sourceUrl 来源 URL
     * @param content 内容摘要
     * @param location 目的地
     * @param userId 用户 ID
     * @return 创建的游记 ID
     */
    @Transactional
    public Long createFromExternal(String title, String sourceUrl, String content, String location, Long userId) {
        TravelNote note = TravelNote.builder()
                .userId(userId)
                .title(title)
                .content(content)
                .sourceType("external")
                .sourceUrl(sourceUrl)
                .location(location)
                .status("active")
                .build();

        travelNoteMapper.insert(note);
        log.info("[TravelNote] 从外部导入游记: id={}, userId={}, source={}", note.getId(), userId, sourceUrl);

        // 自动分块
        chunkAndSaveContent(note.getId(), content, location, null);

        return note.getId();
    }

    /**
     * 更新游记
     */
    @Transactional
    public void updateNote(Long noteId, String title, String content, String location, String tags) {
        TravelNote note = travelNoteMapper.selectById(noteId);
        if (note == null) {
            throw new RuntimeException("游记不存在: " + noteId);
        }

        note.setTitle(title);
        note.setLocation(location);
        note.setTags(tags);
        travelNoteMapper.updateById(note);

        // 如果内容变更，重新分块
        if (content != null && !content.equals(note.getContent())) {
            note.setContent(content);
            // 删除旧分块
            travelNoteChunkMapper.deleteByNoteId(noteId);
            // 重新分块
            chunkAndSaveContent(noteId, content, location, tags);
        }

        log.info("[TravelNote] 更新游记: id={}", noteId);
    }

    /**
     * 删除游记（软删除）
     */
    @Transactional
    public void deleteNote(Long noteId) {
        TravelNote note = travelNoteMapper.selectById(noteId);
        if (note != null) {
            note.setStatus("deleted");
            travelNoteMapper.updateById(note);
            travelNoteChunkMapper.deleteByNoteId(noteId);
            log.info("[TravelNote] 删除游记: id={}", noteId);
        }
    }

    /**
     * 获取用户的所有游记
     */
    public List<TravelNote> getAllNotes() {
        return travelNoteMapper.selectAllActive();
    }

    /**
     * 根据地点搜索游记（无用户限制）
     */
    public List<TravelNote> searchByLocation(String location) {
        return travelNoteMapper.selectByLocation(location);
    }

    /**
     * 获取游记详情
     */
    public TravelNote getNoteById(Long noteId) {
        return travelNoteMapper.selectById(noteId);
    }

    /**
     * 文本分块并保存
     * 使用滑动窗口分块策略，自动生成向量
     * 内存优化：逐条插入数据库
     */
    private void chunkAndSaveContent(Long noteId, String content, String location, String tagsStr) {
        log.info("[TravelNote] chunkAndSaveContent 开始: noteId={}, contentLength={}", noteId, content != null ? content.length() : "null");
        
        if (content == null || content.isEmpty()) {
            log.info("[TravelNote] 内容为空，跳过: noteId={}", noteId);
            return;
        }

        List<String> tags = tagsStr != null ? List.of(tagsStr.split(",")) : List.of();

        log.info("[TravelNote] 开始分块: noteId={}", noteId);
        List<String> chunks = splitIntoChunks(content);
        log.info("[TravelNote] 分块完成: noteId={}, chunkCount={}", noteId, chunks.size());
        
        if (chunks.isEmpty()) {
            log.info("[TravelNote] 没有生成任何分块: noteId={}", noteId);
            return;
        }
        
        int savedCount = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            
            log.info("[TravelNote] 处理分块: noteId={}, index={}/{}", noteId, i + 1, chunks.size());
            
            try {
                // 逐个生成向量
                log.info("[TravelNote] 调用 embeddingService.embed: noteId={}, index={}", noteId, i);
                float[] embedding = embeddingService.embed(chunk);
                log.info("[TravelNote] 向量生成完成: noteId={}, index={}, dimensions={}", noteId, i, embedding.length);
                
                List<String> keywords = extractLocationKeywords(chunk, location, tags);
                if (location != null && !location.isEmpty()) {
                    keywords.add(0, location);
                }
                
                TravelNoteChunk entity = TravelNoteChunk.builder()
                        .noteId(noteId)
                        .chunkText(chunk)
                        .chunkIndex(i)
                        .embedding(embedding)
                        .locationKeywordsStr(String.join(",", keywords))
                        .contentType(classifyChunk(chunk))
                        .build();
                
                // 立即插入数据库，不保留在内存中
                travelNoteChunkMapper.insertOne(entity);
                savedCount++;
                log.info("[TravelNote] 插入完成: noteId={}, index={}, savedCount={}", noteId, i, savedCount);
                
            } catch (Exception e) {
                log.error("[TravelNote] 保存分块失败: noteId={}, index={}, error={}", noteId, i, e.getMessage(), e);
            }
        }

        log.info("[TravelNote] 保存完成: noteId={}, savedCount={}", noteId, savedCount);
    }

    /**
     * ⭐ 语义分块：按语义边界切分，而非固定字数
     */
    private List<String> splitIntoChunks(String text) {
        log.info("[TravelNote] splitIntoChunks: textLength={}", text != null ? text.length() : "null");
        
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        // 限制内容长度，防止 OOM
        String content = text;
        if (text.length() > MAX_CONTENT_LENGTH) {
            log.warn("[TravelNote] 内容过长({}字符)，截断至{}字符", text.length(), MAX_CONTENT_LENGTH);
            content = text.substring(0, MAX_CONTENT_LENGTH);
        }

        // 调用语义分块器
        List<String> chunks = semanticChunker.split(content);
        log.info("[TravelNote] 语义分块完成: {}", chunks.size());
        return chunks;
    }

    /**
     * ⭐ 分类 chunk 的内容类型：scenic / food / accommodation / transport / general
     * 两级信号 + 积分制，不同类别有不同优先级
     */
    private String classifyChunk(String text) {
        if (text == null || text.isEmpty()) return "general";

        // 1. 美食判定（强信号 1 个或弱信号 ≥ 2 个）
        long foodStrong = FOOD_STRONG.stream().filter(kw -> text.contains(kw)).count();
        if (foodStrong >= 1) return "food";
        long foodWeak = FOOD_WEAK.stream().filter(kw -> text.contains(kw)).count();
        if (foodWeak >= 2) return "food";

        // 2. 住宿判定
        long accommodation = ACCOMMODATION.stream().filter(kw -> text.contains(kw)).count();
        if (accommodation >= 2) return "accommodation";

        // 3. 交通判定
        long transport = TRANSPORT.stream().filter(kw -> text.contains(kw)).count();
        if (transport >= 2) return "transport";

        // 4. 景点/游玩判定
        long scenic = SCENIC.stream().filter(kw -> text.contains(kw)).count();
        if (scenic >= 1) return "scenic";

        return "general";
    }

    /**
     * 提取地点关键词
     */
    private List<String> extractLocationKeywords(String chunk, String mainLocation, List<String> tags) {
        // 简单的地点提取：使用正则匹配常见模式
        // 实际项目中可使用 LLM 或 NLP 库提取
        Pattern pattern = Pattern.compile("[\\u4e00-\\u9fa5]{2,10}(?:景点|公园|博物馆|寺庙|广场|街|路|镇|城|山|湖|河|岛|亭|楼|阁|宫|殿|寺|院)");
        Matcher matcher = pattern.matcher(chunk);

        List<String> keywords = new java.util.ArrayList<>();
        if (mainLocation != null && !mainLocation.isEmpty()) {
            keywords.add(mainLocation.trim());
        }
        if (tags != null) {
            for (String tag : tags) {
                String trimmed = tag.trim();
                if (!trimmed.isEmpty() && !keywords.contains(trimmed)) {
                    keywords.add(trimmed);
                }
            }
        }

        while (matcher.find()) {
            String keyword = matcher.group();
            if (!keywords.contains(keyword)) {
                keywords.add(keyword);
            }
        }

        return keywords;
    }

    // ==================== Admin 接口所需方法 ====================

    /**
     * 删除游记的所有分块
     */
    @Transactional
    public void deleteChunks(Long noteId) {
        travelNoteChunkMapper.deleteByNoteId(noteId);
        log.info("[TravelNote] 删除分块: noteId={}", noteId);
    }

    /**
     * 重新分块并生成向量
     */
    @Transactional
    public void rebuildChunks(Long noteId, String content, String location, String tags) {
        // 重新分块
        chunkAndSaveContent(noteId, content, location, tags);
        log.info("[TravelNote] 重新分块完成: noteId={}", noteId);
    }

    /**
     * 全量重建所有游记的分块
     * @return 处理的游记数量
     */
    @Transactional
    public int rebuildAllChunks() {
        List<TravelNote> notes = travelNoteMapper.selectAllActive();
        int count = 0;
        for (TravelNote note : notes) {
            try {
                deleteChunks(note.getId());
                rebuildChunks(note.getId(), note.getContent(), note.getLocation(), note.getTags());
                count++;
            } catch (Exception e) {
                log.error("[TravelNote] 重建失败: noteId={}", note.getId(), e);
            }
        }
        log.info("[TravelNote] 全量重建完成: count={}", count);
        return count;
    }

    /**
     * 搜索分块（用于测试）
     */
    public List<TravelNoteChunk> searchChunks(String location, String query) {
        // 降级方案：基于关键词搜索
        return travelNoteChunkMapper.searchByLocation(location, 10);
    }
}
