package com.example.smartassistant.consumer.service.session;

import com.example.smartassistant.consumer.config.ConversationCompressionProperties;
import com.example.smartassistant.consumer.dto.CompressionCacheEntry;
import com.example.smartassistant.consumer.dto.StructuredPrompt;
import com.example.smartassistant.consumer.entity.ChatMessage;
import com.example.smartassistant.consumer.mapper.ChatMessageMapper;
import com.example.smartassistant.consumer.service.cache.ConversationCompressionCacheService;
import com.example.smartassistant.consumer.service.infrastructure.IntelligentCompressionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 会话历史服务
 * <p>
 * 统一封装对话历史的查询与压缩，对外提供两类接口：
 * <ul>
 *   <li><b>结构化接口</b>：返回 {@link StructuredPrompt.ConversationMessage} 列表，用于 JSON Prompt 构建</li>
 *   <li><b>文本接口</b>：返回格式化字符串，用于文本 Prompt 构建（兼容旧版）</li>
 * </ul>
 * 压缩策略由内部自动判断，调用方无需关心。
 * <p>
 * ⭐ 增量压缩优化：只对新消息进行压缩，结合缓存的历史结果，避免每次全量压缩。
 * 触发条件：每 10 轮对话 或 Token 数超过 ConversationCompressionProperties 中的限制。
 * 压缩截至的消息 ID 会记录到 Redis 缓存中，下次查询时直接从该 ID 之后读取新消息。
 */
@Service
public class ConversationHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryService.class);

    private final ChatMessageMapper chatMessageMapper;
    private final IntelligentCompressionService compressionService;
    private final ConversationCompressionCacheService cacheService;
    private final ConversationCompressionProperties properties;

    public ConversationHistoryService(ChatMessageMapper chatMessageMapper,
                                       IntelligentCompressionService compressionService,
                                       ConversationCompressionCacheService cacheService,
                                       ConversationCompressionProperties properties) {
        this.chatMessageMapper = chatMessageMapper;
        this.compressionService = compressionService;
        this.cacheService = cacheService;
        this.properties = properties;
    }

    /**
     * 获取结构化对话历史（自动压缩 + 缓存 + 增量压缩）
     * <p>推荐用于新版 JSON Prompt 构建</p>
     *
     * @param threadId 会话 ID
     * @return 压缩后的结构化消息列表
     */
    public List<StructuredPrompt.ConversationMessage> getStructuredHistory(String threadId) {
        return getStructuredHistory(threadId, Integer.MAX_VALUE);
    }

    /**
     * 获取结构化对话历史（自动压缩 + 缓存 + 增量压缩）
     *
     * @param threadId   会话 ID
     * @param queryLimit 从数据库查询的最大条数（防止过量查询）
     * @return 压缩后的结构化消息列表
     */
    public List<StructuredPrompt.ConversationMessage> getStructuredHistory(String threadId, int queryLimit) {
        if (threadId == null || threadId.isEmpty()) {
            log.debug("[ConversationHistory] threadId 为空，返回空历史");
            return Collections.emptyList();
        }

        try {
            // Step1: 获取当前消息总数
            long totalCountLong = chatMessageMapper.countBySessionId(threadId);
            int totalCount = (int) Math.min(totalCountLong, Integer.MAX_VALUE);

            if (totalCount == 0) {
                log.debug("[ConversationHistory] threadId={} 无历史记录", threadId);
                return Collections.emptyList();
            }

            // Step2: 尝试命中 Redis 缓存
            Optional<CompressionCacheEntry> cached = cacheService.get(threadId);

            if (cached.isPresent()) {
                CompressionCacheEntry cacheEntry = cached.get();

                // 情况 A：消息总数未变化，直接返回缓存（无新消息）
                if (cacheService.isValid(cacheEntry, totalCount)) {
                    log.debug("[ConversationHistory] 压缩缓存命中(无新消息): threadId={}, rounds={}",
                            threadId, cacheEntry.getOriginalRoundCount());
                    return cacheEntry.getCompressedHistory();
                }

                // 情况 B：有缓存但有新消息，尝试增量压缩
                if (cacheEntry.getLastCompressedMessageId() != null) {
                    log.info("[ConversationHistory] 检测到新消息，尝试增量压缩: threadId={}, lastId={}, totalCount={}",
                            threadId, cacheEntry.getLastCompressedMessageId(), totalCount);

                    List<StructuredPrompt.ConversationMessage> result = incrementalCompress(
                            threadId, cacheEntry, totalCount);

                    if (result != null) {
                        return result;
                    }
                    // 增量压缩失败，降级到全量压缩
                }
            }

            // 情况 C：无缓存或缓存无效或增量压缩失败，执行全量压缩
            return fullCompress(threadId, totalCount, queryLimit);

        } catch (Exception e) {
            log.error("[ConversationHistory] 查询历史失败: threadId={}, error={}", threadId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ==================== 增量压缩核心方法 ====================

    /**
     * 增量压缩：只处理新消息，与缓存历史合并
     *
     * @param threadId     会话 ID
     * @param cacheEntry   缓存条目
     * @param totalCount   当前消息总数
     * @return 压缩后的历史，失败返回 null（触发全量压缩）
     */
    private List<StructuredPrompt.ConversationMessage> incrementalCompress(
            String threadId,
            CompressionCacheEntry cacheEntry,
            int totalCount) {

        try {
            Long lastId = cacheEntry.getLastCompressedMessageId();

            // 查询新消息（lastId 之后）
            List<ChatMessage> newMessages = chatMessageMapper.findBySessionIdAndIdAfterOrderByCreatedAtAsc(
                    threadId, lastId);

            if (newMessages == null || newMessages.isEmpty()) {
                // 理论上不会走到这里（因为 isValid 已经判断 totalCount 变化了）
                log.warn("[ConversationHistory] 增量压缩时未查询到新消息，降级到全量压缩");
                return null;
            }

            log.info("[ConversationHistory] 增量压缩: threadId={}, 新消息={}条, 缓存历史={}条",
                    threadId, newMessages.size(), cacheEntry.getCompressedHistory().size());

            // 转换新消息为结构化格式
            List<StructuredPrompt.ConversationMessage> newStructured = convertToStructured(newMessages);

            // 合并：缓存的压缩历史 + 新消息
            List<StructuredPrompt.ConversationMessage> merged = new ArrayList<>(
                    cacheEntry.getCompressedHistory().size() + newStructured.size());
            merged.addAll(cacheEntry.getCompressedHistory());
            merged.addAll(newStructured);

            // 判断是否需要重新压缩（基于合并后的总轮数 / Token 数）
            int mergedRounds = merged.size() / 2;
            int estimatedTokens = compressionService.estimateTokens(merged);
            int totalChars = merged.stream()
                    .mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0)
                    .sum();

            boolean needsCompression = mergedRounds > properties.getMaxRoundsBeforeCompress()
                    || estimatedTokens > properties.getMaxTokensBeforeCompress()
                    || totalChars > properties.getMaxCharsBeforeCompress();

            List<StructuredPrompt.ConversationMessage> finalHistory;
            boolean compressed;

            if (needsCompression) {
                // 需要重新压缩
                IntelligentCompressionService.CompressionResult result = compressionService.smartCompress(merged);
                finalHistory = result.history();
                compressed = result.compressed();

                log.info("[ConversationHistory] 增量压缩后重新压缩: threadId={}, 原始={}轮, 压缩后={}轮, strategy={}",
                        threadId, mergedRounds, finalHistory.size() / 2, result.strategy());
            } else {
                // 不需要压缩，直接使用合并结果
                finalHistory = merged;
                compressed = false;

                log.debug("[ConversationHistory] 增量压缩: 无需重新压缩, 当前={}轮, tokens≈{}",
                        mergedRounds, estimatedTokens);
            }

            // 获取最新的消息 ID（用于下次增量）
            Long newLastId = newMessages.get(newMessages.size() - 1).getId();

            // 写入缓存
            IntelligentCompressionService.CompressionResult cacheResult =
                    new IntelligentCompressionService.CompressionResult(
                            finalHistory, compressed,
                            compressionService.estimateTokens(finalHistory),
                            compressed ? "incremental" : "none"
                    );
            cacheService.put(threadId, cacheResult, totalCount, newLastId);

            return finalHistory;

        } catch (Exception e) {
            log.warn("[ConversationHistory] 增量压缩失败，降级到全量压缩: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 全量压缩方法 ====================

    /**
     * 全量压缩：查询所有消息并压缩
     */
    private List<StructuredPrompt.ConversationMessage> fullCompress(
            String threadId,
            int totalCount,
            int queryLimit) {

        log.info("[ConversationHistory] 执行全量压缩: threadId={}, totalCount={}", threadId, totalCount);

        // 从数据库查询完整历史
        List<ChatMessage> messages = chatMessageMapper.findBySessionIdOrderByCreatedAtAsc(threadId);

        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }

        // 限制查询数量
        if (messages.size() > queryLimit) {
            messages = messages.subList(messages.size() - queryLimit, messages.size());
        }

        // 转换为结构化消息
        List<StructuredPrompt.ConversationMessage> structured = convertToStructured(messages);

        // 智能压缩
        IntelligentCompressionService.CompressionResult result = compressionService.smartCompress(structured);

        if (result.compressed()) {
            log.info("[ConversationHistory] 全量压缩完成: threadId={}, 原始={}轮, 压缩后={}轮, strategy={}, tokens≈{}",
                    threadId, structured.size() / 2, result.history().size() / 2,
                    result.strategy(), result.estimatedTokens());
        } else {
            log.debug("[ConversationHistory] 全量压缩: 无需压缩, threadId={}, 共{}轮, tokens≈{}",
                    threadId, structured.size() / 2, result.estimatedTokens());
        }

        // 获取最新的消息 ID（用于下次增量压缩）
        Long lastId = messages.get(messages.size() - 1).getId();

        // 写入缓存
        cacheService.put(threadId, result, messages.size(), lastId);

        return result.history();
    }

    // ==================== 其他公开方法 ====================

    /**
     * 获取格式化的文本历史（自动压缩）
     * <p>兼容旧版文本 Prompt 构建</p>
     *
     * @param threadId 会话 ID
     * @param limit    限制返回条数（默认 5）
     * @return 格式化的对话历史字符串
     */
    public String getRecentHistory(String threadId, int limit) {
        List<StructuredPrompt.ConversationMessage> history = getStructuredHistory(threadId, limit * 2);

        if (history.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            var msg = history.get(i);
            String roleLabel = "system".equalsIgnoreCase(msg.getRole()) ? "系统"
                    : ("user".equalsIgnoreCase(msg.getRole()) ? "用户" : "助手");
            sb.append(String.format("%d. %s: %s\n", i + 1, roleLabel, truncate(msg.getContent(), 200)));
        }

        return sb.toString();
    }

    /**
     * 获取最近 5 条对话历史（默认）
     */
    public String getRecentHistory(String threadId) {
        return getRecentHistory(threadId, 5);
    }

    /**
     * 获取原始消息列表（不压缩，用于管理后台等场景）
     */
    public List<ChatMessage> getRawMessages(String threadId, int limit) {
        if (threadId == null || threadId.isEmpty()) {
            return Collections.emptyList();
        }
        List<ChatMessage> messages = chatMessageMapper.findBySessionIdOrderByCreatedAtAsc(threadId);
        if (messages.size() > limit) {
            messages = messages.subList(messages.size() - limit, messages.size());
        }
        return messages;
    }

    /**
     * 手动使指定会话的压缩缓存失效
     * <p>在删除历史消息等场景下调用</p>
     *
     * @param threadId 会话 ID
     */
    public void invalidateCompressionCache(String threadId) {
        cacheService.evict(threadId);
        log.info("[ConversationHistory] 手动失效压缩缓存: threadId={}", threadId);
    }

    // ==================== 私有方法 ====================

    private List<StructuredPrompt.ConversationMessage> convertToStructured(List<ChatMessage> messages) {
        List<StructuredPrompt.ConversationMessage> result = new ArrayList<>(messages.size());
        for (ChatMessage msg : messages) {
            if (msg == null || msg.getContent() == null) continue;
            String role = Boolean.TRUE.equals(msg.getIsUser()) ? "user" : "agent";
            result.add(StructuredPrompt.ConversationMessage.builder()
                    .role(role)
                    .content(msg.getContent())
                    .timestamp(msg.getCreatedAt() != null
                            ? msg.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            : System.currentTimeMillis())
                    .build());
        }
        return result;
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
    }
}
