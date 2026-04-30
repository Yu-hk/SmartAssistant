package com.example.smartassistant.consumer.service.cache;

import com.example.smartassistant.consumer.config.ConversationCompressionProperties;
import com.example.smartassistant.consumer.dto.CompressionCacheEntry;
import com.example.smartassistant.consumer.dto.StructuredPrompt;
import com.example.smartassistant.consumer.service.infrastructure.IntelligentCompressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ConversationCompressionCacheService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class ConversationCompressionCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private ConversationCompressionProperties properties;

    @InjectMocks
    private ConversationCompressionCacheService cacheService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(properties.isCacheEnabled()).thenReturn(true);
        lenient().when(properties.getCacheTtlMinutes()).thenReturn(30);
    }

    @Test
    void testGet_CacheHit() {
        String threadId = "test-thread-001";
        CompressionCacheEntry entry = buildCacheEntry(10, "truncate");
        when(valueOperations.get("a2a:compression:" + threadId)).thenReturn(entry);

        Optional<CompressionCacheEntry> result = cacheService.get(threadId);

        assertTrue(result.isPresent());
        assertEquals(10, result.get().getOriginalMessageCount());
        assertEquals("truncate", result.get().getStrategy());
    }

    @Test
    void testGet_CacheMiss() {
        String threadId = "test-thread-002";
        when(valueOperations.get("a2a:compression:" + threadId)).thenReturn(null);

        Optional<CompressionCacheEntry> result = cacheService.get(threadId);

        assertFalse(result.isPresent());
    }

    @Test
    void testGet_CacheDisabled() {
        when(properties.isCacheEnabled()).thenReturn(false);

        Optional<CompressionCacheEntry> result = cacheService.get("any-thread");

        assertFalse(result.isPresent());
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    void testGet_EmptyThreadId() {
        assertFalse(cacheService.get("").isPresent());
        assertFalse(cacheService.get(null).isPresent());
    }

    @Test
    void testGet_LinkedHashMapConversion() {
        // Redis JSON 序列化后返回的可能是 LinkedHashMap
        String threadId = "test-thread-003";
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("originalMessageCount", 8);
        map.put("originalRoundCount", 4);
        map.put("strategy", "extract");
        map.put("estimatedTokens", 100);
        map.put("timestamp", System.currentTimeMillis());
        map.put("compressed", true);
        map.put("compressedHistory", List.of(
                java.util.Map.of("role", "user", "content", "你好", "timestamp", 123L)
        ));

        when(valueOperations.get("a2a:compression:" + threadId)).thenReturn(map);

        Optional<CompressionCacheEntry> result = cacheService.get(threadId);

        assertTrue(result.isPresent());
        assertEquals(8, result.get().getOriginalMessageCount());
        assertEquals("extract", result.get().getStrategy());
    }

    @Test
    void testPut_Success() {
        String threadId = "test-thread-004";
        List<StructuredPrompt.ConversationMessage> history = List.of(
                StructuredPrompt.ConversationMessage.builder().role("user").content("test").build()
        );
        var result = new IntelligentCompressionService.CompressionResult(history, true, 50, "truncate");

        cacheService.put(threadId, result, 10);

        verify(valueOperations).set(
                eq("a2a:compression:" + threadId),
                any(CompressionCacheEntry.class),
                any(java.time.Duration.class)
        );
    }

    @Test
    void testPut_CacheDisabled() {
        when(properties.isCacheEnabled()).thenReturn(false);

        var result = new IntelligentCompressionService.CompressionResult(List.of(), false, 0, "none");
        cacheService.put("thread", result, 0);

        verify(valueOperations, never()).set(anyString(), any(), any(java.time.Duration.class));
    }

    @Test
    void testPut_NullInputs() {
        // 不应抛出异常
        assertDoesNotThrow(() -> cacheService.put(null, null, 0));
        assertDoesNotThrow(() -> cacheService.put("", null, 0));
    }

    @Test
    void testEvict_Success() {
        String threadId = "test-thread-005";
        when(redisTemplate.delete("a2a:compression:" + threadId)).thenReturn(true);

        assertDoesNotThrow(() -> cacheService.evict(threadId));
        verify(redisTemplate).delete("a2a:compression:" + threadId);
    }

    @Test
    void testEvict_NullOrEmpty() {
        assertDoesNotThrow(() -> cacheService.evict(null));
        assertDoesNotThrow(() -> cacheService.evict(""));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void testIsValid() {
        CompressionCacheEntry entry = buildCacheEntry(10, "truncate");

        assertTrue(cacheService.isValid(entry, 10));
        assertFalse(cacheService.isValid(entry, 12));
        assertFalse(cacheService.isValid(null, 10));

        CompressionCacheEntry nullCount = new CompressionCacheEntry();
        assertFalse(cacheService.isValid(nullCount, 10));
    }

    // ==================== 辅助方法 ====================

    private CompressionCacheEntry buildCacheEntry(int messageCount, String strategy) {
        return CompressionCacheEntry.builder()
                .originalMessageCount(messageCount)
                .originalRoundCount(messageCount / 2)
                .strategy(strategy)
                .estimatedTokens(100)
                .timestamp(System.currentTimeMillis())
                .compressed(true)
                .compressedHistory(List.of(
                        StructuredPrompt.ConversationMessage.builder()
                                .role("user")
                                .content("test")
                                .timestamp(System.currentTimeMillis())
                                .build()
                ))
                .build();
    }
}
