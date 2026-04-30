package com.example.smartassistant.consumer.service.session;

import com.example.smartassistant.consumer.dto.CompressionCacheEntry;
import com.example.smartassistant.consumer.dto.StructuredPrompt;
import com.example.smartassistant.consumer.entity.ChatMessage;
import com.example.smartassistant.consumer.mapper.ChatMessageMapper;
import com.example.smartassistant.consumer.service.cache.ConversationCompressionCacheService;
import com.example.smartassistant.consumer.service.infrastructure.IntelligentCompressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ConversationHistoryService 单元测试（含缓存场景）
 */
@ExtendWith(MockitoExtension.class)
class ConversationHistoryServiceTest {

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private IntelligentCompressionService compressionService;

    @Mock
    private ConversationCompressionCacheService cacheService;

    @InjectMocks
    private ConversationHistoryService historyService;

    @BeforeEach
    void setUp() {
        // mock smartCompress 默认行为：原样返回（使用 lenient 避免未使用时报错）
        lenient().when(compressionService.smartCompress(anyList()))
                .thenAnswer(inv -> new IntelligentCompressionService.CompressionResult(
                        inv.getArgument(0), false, 0, "none"
                ));
    }

    // ==================== 缓存相关测试 ====================

    @Test
    void testGetStructuredHistory_CacheHit() {
        String threadId = "test-thread-cache-hit";
        List<StructuredPrompt.ConversationMessage> cachedHistory = List.of(
                StructuredPrompt.ConversationMessage.builder().role("user").content("缓存命中").build()
        );
        CompressionCacheEntry entry = CompressionCacheEntry.builder()
                .originalMessageCount(4)
                .compressedHistory(cachedHistory)
                .strategy("truncate")
                .build();

        when(chatMessageMapper.countBySessionId(threadId)).thenReturn(4L);
        when(cacheService.get(threadId)).thenReturn(Optional.of(entry));
        when(cacheService.isValid(entry, 4)).thenReturn(true);

        var result = historyService.getStructuredHistory(threadId);

        assertEquals(1, result.size());
        assertEquals("缓存命中", result.get(0).getContent());
        // 缓存命中时不应查数据库详细历史
        verify(chatMessageMapper, never()).findBySessionIdOrderByCreatedAtAsc(threadId);
    }

    @Test
    void testGetStructuredHistory_CacheMiss() {
        String threadId = "test-thread-cache-miss";
        List<ChatMessage> dbMessages = List.of(
                createMessage(1L, threadId, true, "北京有什么好吃的？"),
                createMessage(2L, threadId, false, "推荐烤鸭和涮羊肉。")
        );

        when(chatMessageMapper.countBySessionId(threadId)).thenReturn(2L);
        when(cacheService.get(threadId)).thenReturn(Optional.empty());
        when(chatMessageMapper.findBySessionIdOrderByCreatedAtAsc(threadId)).thenReturn(dbMessages);

        var result = historyService.getStructuredHistory(threadId);

        assertEquals(2, result.size());
        verify(cacheService).put(eq(threadId), any(IntelligentCompressionService.CompressionResult.class), eq(2));
    }

    @Test
    void testGetStructuredHistory_CacheInvalid() {
        String threadId = "test-thread-cache-invalid";
        CompressionCacheEntry entry = CompressionCacheEntry.builder()
                .originalMessageCount(4)
                .build();
        List<ChatMessage> dbMessages = List.of(
                createMessage(1L, threadId, true, "新消息1"),
                createMessage(2L, threadId, false, "新回复1"),
                createMessage(3L, threadId, true, "新消息2")
        );

        when(chatMessageMapper.countBySessionId(threadId)).thenReturn(3L); // 现在有 3 条，缓存是 4 条
        when(cacheService.get(threadId)).thenReturn(Optional.of(entry));
        when(cacheService.isValid(entry, 3)).thenReturn(false);
        when(chatMessageMapper.findBySessionIdOrderByCreatedAtAsc(threadId)).thenReturn(dbMessages);

        var result = historyService.getStructuredHistory(threadId);

        assertEquals(3, result.size());
        // 缓存失效后应重新查询并写入新缓存
        verify(chatMessageMapper).findBySessionIdOrderByCreatedAtAsc(threadId);
        verify(cacheService).put(eq(threadId), any(IntelligentCompressionService.CompressionResult.class), eq(3));
    }

    @Test
    void testInvalidateCompressionCache() {
        String threadId = "test-thread-evict";
        assertDoesNotThrow(() -> historyService.invalidateCompressionCache(threadId));
        verify(cacheService).evict(threadId);
    }

    // ==================== 原有测试 ====================

    @Test
    void testGetStructuredHistory_EmptyThreadId() {
        var result = historyService.getStructuredHistory("");
        assertTrue(result.isEmpty());

        var result2 = historyService.getStructuredHistory(null);
        assertTrue(result2.isEmpty());
    }

    @Test
    void testGetStructuredHistory_Normal() {
        String threadId = "test-thread-001";
        List<ChatMessage> dbMessages = List.of(
                createMessage(1L, threadId, true, "北京有什么好吃的？"),
                createMessage(2L, threadId, false, "推荐烤鸭和涮羊肉。")
        );

        when(chatMessageMapper.countBySessionId(threadId)).thenReturn(2L);
        when(cacheService.get(threadId)).thenReturn(Optional.empty());
        when(chatMessageMapper.findBySessionIdOrderByCreatedAtAsc(threadId)).thenReturn(dbMessages);

        var result = historyService.getStructuredHistory(threadId);

        assertEquals(2, result.size());
        assertEquals("user", result.get(0).getRole());
        assertEquals("北京有什么好吃的？", result.get(0).getContent());
        assertEquals("agent", result.get(1).getRole());
    }

    @Test
    void testGetStructuredHistory_WithCompression() {
        String threadId = "test-thread-002";
        List<ChatMessage> dbMessages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            dbMessages.add(createMessage((long) i, threadId, true, "问题 " + i));
            dbMessages.add(createMessage((long) i + 100, threadId, false, "回答 " + i));
        }

        when(chatMessageMapper.countBySessionId(threadId)).thenReturn(40L);
        when(cacheService.get(threadId)).thenReturn(Optional.empty());
        when(chatMessageMapper.findBySessionIdOrderByCreatedAtAsc(threadId)).thenReturn(dbMessages);

        // 模拟压缩后只剩 6 条
        List<StructuredPrompt.ConversationMessage> compressed = List.of(
                StructuredPrompt.ConversationMessage.builder().role("system").content("[历史对话摘要] ...").build(),
                StructuredPrompt.ConversationMessage.builder().role("user").content("最近的问题").build(),
                StructuredPrompt.ConversationMessage.builder().role("agent").content("最近的回答").build()
        );
        when(compressionService.smartCompress(anyList()))
                .thenReturn(new IntelligentCompressionService.CompressionResult(
                        compressed, true, 50, "truncate"
                ));

        var result = historyService.getStructuredHistory(threadId);

        assertEquals(3, result.size());
        assertEquals("system", result.get(0).getRole());
    }

    @Test
    void testGetStructuredHistory_QueryLimit() {
        String threadId = "test-thread-003";
        List<ChatMessage> dbMessages = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            dbMessages.add(createMessage((long) i, threadId, i % 2 == 0, "消息 " + i));
        }

        when(chatMessageMapper.countBySessionId(threadId)).thenReturn(100L);
        when(cacheService.get(threadId)).thenReturn(Optional.empty());
        when(chatMessageMapper.findBySessionIdOrderByCreatedAtAsc(threadId)).thenReturn(dbMessages);

        var result = historyService.getStructuredHistory(threadId, 10);

        // compressionService 应该只收到 10 条消息（queryLimit=10）
        verify(compressionService).smartCompress(argThat(list -> list.size() == 10));
    }

    @Test
    void testGetRecentHistory_TextFormat() {
        String threadId = "test-thread-004";
        List<ChatMessage> dbMessages = List.of(
                createMessage(1L, threadId, true, "北京有什么好吃的？"),
                createMessage(2L, threadId, false, "推荐烤鸭。")
        );

        when(chatMessageMapper.countBySessionId(threadId)).thenReturn(2L);
        when(cacheService.get(threadId)).thenReturn(Optional.empty());
        when(chatMessageMapper.findBySessionIdOrderByCreatedAtAsc(threadId)).thenReturn(dbMessages);

        String text = historyService.getRecentHistory(threadId, 5);

        assertTrue(text.contains("用户:"));
        assertTrue(text.contains("助手:"));
        assertTrue(text.contains("北京有什么好吃的？"));
    }

    @Test
    void testGetRecentHistory_Empty() {
        String threadId = "empty-thread";
        when(chatMessageMapper.countBySessionId(threadId)).thenReturn(0L);
        when(cacheService.get(threadId)).thenReturn(Optional.empty());
        when(chatMessageMapper.findBySessionIdOrderByCreatedAtAsc(threadId)).thenReturn(Collections.emptyList());

        String text = historyService.getRecentHistory(threadId);
        assertTrue(text.isEmpty());
    }

    @Test
    void testGetRawMessages() {
        String threadId = "test-thread-005";
        List<ChatMessage> dbMessages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            dbMessages.add(createMessage((long) i, threadId, true, "消息 " + i));
        }

        when(chatMessageMapper.findBySessionIdOrderByCreatedAtAsc(threadId)).thenReturn(dbMessages);

        var result = historyService.getRawMessages(threadId, 5);

        assertEquals(5, result.size());
        assertEquals("消息 15", result.get(0).getContent());
    }

    @Test
    void testMapperExceptionHandling() {
        String threadId = "error-thread";
        when(chatMessageMapper.countBySessionId(threadId)).thenReturn(5L);
        when(cacheService.get(threadId)).thenReturn(Optional.empty());
        when(chatMessageMapper.findBySessionIdOrderByCreatedAtAsc(threadId))
                .thenThrow(new RuntimeException("数据库异常"));

        var result = historyService.getStructuredHistory(threadId);
        assertTrue(result.isEmpty());
    }

    // ==================== 辅助方法 ====================

    private ChatMessage createMessage(Long id, String sessionId, boolean isUser, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setId(id);
        msg.setSessionId(sessionId);
        msg.setIsUser(isUser);
        msg.setContent(content);
        msg.setCreatedAt(LocalDateTime.now());
        return msg;
    }
}
