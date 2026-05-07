package com.example.smartassistant.consumer.service.cache;

import com.example.smartassistant.consumer.config.ConversationCompressionProperties;
import com.example.smartassistant.consumer.dto.CompressionCacheEntry;
import com.example.smartassistant.consumer.service.infrastructure.IntelligentCompressionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 对话压缩结果缓存服务
 * <p>
 * 基于 Redis 缓存会话的压缩后历史，避免每次请求重复计算。
 * 缓存 key: a2a:compression:{threadId}
 * </p>
 */
@Service
public class ConversationCompressionCacheService {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompressionCacheService.class);
    private static final String CACHE_KEY_PREFIX = "a2a:compression:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ConversationCompressionProperties properties;
    private final ObjectMapper objectMapper;

    public ConversationCompressionCacheService(RedisTemplate<String, Object> redisTemplate,
                                                ConversationCompressionProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 从缓存获取压缩结果
     *
     * @param threadId 会话 ID
     * @return 缓存条目（如果存在且有效）
     */
    public Optional<CompressionCacheEntry> get(String threadId) {
        if (!properties.isCacheEnabled() || threadId == null || threadId.isEmpty()) {
            return Optional.empty();
        }

        try {
            String key = buildKey(threadId);
            Object value = redisTemplate.opsForValue().get(key);

            if (value == null) {
                return Optional.empty();
            }

            // Redis JSON 序列化后可能是 LinkedHashMap，需要转换
            CompressionCacheEntry entry;
            if (value instanceof CompressionCacheEntry) {
                entry = (CompressionCacheEntry) value;
            } else {
                entry = objectMapper.convertValue(value, CompressionCacheEntry.class);
            }

            log.debug("[CompressionCache] 缓存命中: threadId={}", threadId);
            return Optional.of(entry);

        } catch (Exception e) {
            log.warn("[CompressionCache] 读取缓存失败: threadId={}, error={}", threadId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 将压缩结果写入缓存
     *
     * @param threadId 会话 ID
     * @param result   压缩结果
     * @param originalMessageCount 原始消息条数
     * @param lastCompressedMessageId 压缩截至的消息ID（用于增量压缩）
     */
    public void put(String threadId, IntelligentCompressionService.CompressionResult result,
                    int originalMessageCount, Long lastCompressedMessageId) {
        if (!properties.isCacheEnabled() || threadId == null || threadId.isEmpty() || result == null) {
            return;
        }

        try {
            String key = buildKey(threadId);

            CompressionCacheEntry entry = CompressionCacheEntry.builder()
                    .compressedHistory(result.history())
                    .originalMessageCount(originalMessageCount)
                    .originalRoundCount(originalMessageCount / 2)
                    .strategy(result.strategy())
                    .estimatedTokens(result.estimatedTokens())
                    .timestamp(System.currentTimeMillis())
                    .compressed(result.compressed())
                    .lastCompressedMessageId(lastCompressedMessageId)  // ⭐ 记录压缩截至的消息ID
                    .build();

            Duration ttl = Duration.ofMinutes(properties.getCacheTtlMinutes());
            redisTemplate.opsForValue().set(key, entry, ttl);

            log.debug("[CompressionCache] 缓存写入: threadId={}, ttl={}min, rounds={}, lastId={}",
                    threadId, properties.getCacheTtlMinutes(), entry.getOriginalRoundCount(), lastCompressedMessageId);

        } catch (Exception e) {
            log.warn("[CompressionCache] 写入缓存失败: threadId={}, error={}", threadId, e.getMessage());
        }
    }

    /**
     * 使指定会话的压缩缓存失效
     * <p>在手动删除历史消息等场景下调用</p>
     *
     * @param threadId 会话 ID
     */
    public void evict(String threadId) {
        if (threadId == null || threadId.isEmpty()) {
            return;
        }

        try {
            String key = buildKey(threadId);
            redisTemplate.delete(key);
            log.debug("[CompressionCache] 缓存失效: threadId={}", threadId);
        } catch (Exception e) {
            log.warn("[CompressionCache] 缓存失效失败: threadId={}, error={}", threadId, e.getMessage());
        }
    }

    /**
     * 判断缓存是否有效（原始消息数未变化）
     *
     * @param entry              缓存条目
     * @param currentMessageCount 当前数据库中的消息数
     * @return true = 缓存有效
     */
    public boolean isValid(CompressionCacheEntry entry, int currentMessageCount) {
        if (entry == null || entry.getOriginalMessageCount() == null) {
            return false;
        }
        return entry.getOriginalMessageCount() == currentMessageCount;
    }

    private String buildKey(String threadId) {
        return CACHE_KEY_PREFIX + threadId;
    }
}
