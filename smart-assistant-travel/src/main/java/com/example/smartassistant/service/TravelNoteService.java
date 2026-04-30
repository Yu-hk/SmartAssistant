package com.example.smartassistant.service;

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

    // 分块大小配置
    private static final int CHUNK_SIZE = 300;  // 每块字符数
    private static final int MAX_CONTENT_LENGTH = 50000;  // 最大内容长度（防止 OOM）
    private static final int CHUNK_OVERLAP = 50; // 块重叠字符数

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
    public List<TravelNote> getUserNotes(Long userId) {
        return travelNoteMapper.selectByUserId(userId);
    }

    /**
     * 根据地点搜索游记（与用户无关，所有游记共享）
     */
    public List<TravelNote> searchByLocation(Long userId, String location) {
        return travelNoteMapper.selectByLocationKeywords(location, userId);
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
     * 滑动窗口分块（内存优化版）
     */
    private List<String> splitIntoChunks(String text) {
        log.info("[TravelNote] splitIntoChunks: textLength={}", text != null ? text.length() : "null");
        
        if (text == null || text.isEmpty()) {
            log.info("[TravelNote] splitIntoChunks: text is null or empty");
            return List.of();
        }
        
        // 限制内容长度，防止 OOM
        String content = text;
        if (text.length() > MAX_CONTENT_LENGTH) {
            log.warn("[TravelNote] 内容过长({}字符)，截断至{}字符", text.length(), MAX_CONTENT_LENGTH);
            content = text.substring(0, MAX_CONTENT_LENGTH);
        }
        
        log.info("[TravelNote] splitIntoChunks: contentLength={}, CHUNK_SIZE={}", content.length(), CHUNK_SIZE);
        
        if (content.length() <= CHUNK_SIZE) {
            log.info("[TravelNote] splitIntoChunks: short content, returning single chunk");
            return List.of(content);
        }

        log.info("[TravelNote] splitIntoChunks: starting chunking loop");
        
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int iteration = 0;

        while (start < content.length()) {
            iteration++;
            if (iteration % 100 == 0) {
                log.info("[TravelNote] splitIntoChunks: iteration={}, start={}, contentLength={}", iteration, start, content.length());
            }

            int end = Math.min(start + CHUNK_SIZE, content.length());

            // 尽量在句子边界切分
            if (end < content.length()) {
                int lastPeriod = content.lastIndexOf("。", end);
                int lastNewline = content.lastIndexOf("\n", end);
                int boundary = Math.max(lastPeriod, lastNewline);

                // ⭐ 修复：仅当 boundary 能让 end 往前推（且仍超过 start + CHUNK_SIZE/2）时才采用
                // 否则 end 过小会导致 start 倒退，引发无限重复分块
                if (boundary > start + CHUNK_SIZE / 2) {
                    end = boundary + 1;
                }
            }

            String chunk = content.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            // ⭐ 修复核心：确保 nextStart 严格大于当前 start，防止死循环
            int nextStart = end - CHUNK_OVERLAP;
            if (nextStart <= start) {
                // 句子边界回退过多导致 nextStart 没有推进，强制向前移动
                nextStart = end;
            }
            start = nextStart;
        }

        log.info("[TravelNote] splitIntoChunks: completed, chunkCount={}", chunks.size());
        return chunks;
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
    public List<TravelNoteChunk> searchChunks(String location, String query, Long userId) {
        // 降级方案：基于关键词搜索
        return travelNoteChunkMapper.searchByLocation(location, userId, 10);
    }
}
