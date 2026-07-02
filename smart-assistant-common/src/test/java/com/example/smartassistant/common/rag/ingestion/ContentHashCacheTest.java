package com.example.smartassistant.common.rag.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ContentHashCache} 的单元测试。
 * <p>
 * 覆盖：首次摄入检测、变更检测、跳过检测、清除、空值处理。
 * </p>
 */
class ContentHashCacheTest {

    private ContentHashCache cache;

    @BeforeEach
    void setUp() {
        cache = new ContentHashCache();
    }

    @Test
    @DisplayName("首次摄入视为已变更")
    void hasChanged_firstIngestIsChange() {
        assertTrue(cache.hasChanged("ORD-001", "abc123"), "从未缓存的文档应视为已变更");
    }

    @Test
    @DisplayName("内容不变视为未变更")
    void hasChanged_unchangedContent() {
        cache.put("ORD-001", "abc123");
        assertFalse(cache.hasChanged("ORD-001", "abc123"), "hash 相同应视为未变更");
    }

    @Test
    @DisplayName("内容变化视为已变更")
    void hasChanged_changedContent() {
        cache.put("ORD-001", "abc123");
        assertTrue(cache.hasChanged("ORD-001", "def456"), "hash 不同应视为已变更");
    }

    @Test
    @DisplayName("needsReingest 在未变更时返回 false 且不抛异常")
    void needsReingest_unchanged() {
        cache.put("PROD-001", "hash_v1");
        assertFalse(cache.needsReingest("PROD-001", "hash_v1"));
    }

    @Test
    @DisplayName("needsReingest 在变更时返回 true")
    void needsReingest_changed() {
        cache.put("PROD-001", "old_hash");
        assertTrue(cache.needsReingest("PROD-001", "new_hash"));
    }

    @Test
    @DisplayName("needsReingest 在首次摄入时返回 true")
    void needsReingest_firstIngest() {
        assertTrue(cache.needsReingest("NEW-001", "first_hash"));
    }

    @Test
    @DisplayName("remove 后视为首次摄入")
    void remove_resetsState() {
        cache.put("ORD-001", "abc123");
        cache.remove("ORD-001");
        assertTrue(cache.hasChanged("ORD-001", "abc123"), "remove 后应视为首次摄入");
    }

    @Test
    @DisplayName("clear 清空全部缓存")
    void clear_resetsAll() {
        cache.put("A", "h1");
        cache.put("B", "h2");
        cache.clear();
        assertEquals(0, cache.size());
        assertTrue(cache.hasChanged("A", "h1"));
    }

    @Test
    @DisplayName("null key 不抛异常")
    void handlesNullKey() {
        assertDoesNotThrow(() -> cache.put(null, "abc"));
        assertTrue(cache.hasChanged("X", "abc"));
    }

    @Test
    @DisplayName("get 未缓存返回空字符串")
    void get_uncachedReturnsEmpty() {
        assertEquals("", cache.get("nonexistent"));
    }

    @Test
    @DisplayName("put 后 get 返回对应值")
    void putAndGet() {
        cache.put("ORD-001", "myhash");
        assertEquals("myhash", cache.get("ORD-001"));
    }

    @Test
    @DisplayName("size 返回正确计数")
    void size_tracksEntries() {
        assertEquals(0, cache.size());
        cache.put("A", "h1");
        assertEquals(1, cache.size());
        cache.put("B", "h2");
        assertEquals(2, cache.size());
        cache.remove("A");
        assertEquals(1, cache.size());
    }
}
