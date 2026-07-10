/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.util;

import com.example.smartassistant.common.rag.ingestion.ContentHashCache;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HashUtil} + {@link ContentHashCache} 单元测试。
 * <p>
 * HashUtil 覆盖：规范化（时间戳/页脚/控制字符/空白）、SHA-256 一致性、聚合 hash。
 * ContentHashCache 覆盖：put/get/hasChanged/needsReingest/chunk API/diffChunks。
 * </p>
 */
@DisplayName("HashUtil + ContentHashCache 单元测试")
class HashUtilAndContentHashCacheTest {

    // ==================== HashUtil ====================

    @Nested
    @DisplayName("HashUtil.normalizeText 规范化")
    class NormalizeTextTest {

        @Test @DisplayName("去除时间戳行")
        void removesTimestampLine() {
            String input = "正文内容\n更新时间：2026-06-29 13:30\n更多内容";
            String result = HashUtil.normalizeText(input);
            assertFalse(result.contains("更新时间"), "应去除更新时间行");
            assertTrue(result.contains("正文内容"));
            assertTrue(result.contains("更多内容"));
        }

        @Test @DisplayName("去除日期时间行")
        void removesDateLine() {
            String input = "2026-06-30 14:00 北京\n正文";
            String result = HashUtil.normalizeText(input);
            assertEquals("正文", result);
        }

        @Test @DisplayName("去除页脚标记")
        void removesPageFooter() {
            String input = "内容\n——第 3 页——\n结尾";
            String result = HashUtil.normalizeText(input);
            assertFalse(result.contains("第"), "应去除页脚");
            assertEquals("内容 结尾", result);
        }

        @Test @DisplayName("去除控制字符")
        void removesControlChars() {
            String input = "ab\u0000cd\u0001ef";
            assertEquals("abcdef", HashUtil.normalizeText(input));
        }

        @Test @DisplayName("标准化连续空白")
        void collapsesWhitespace() {
            String input = "a   b\t\tc\nd\ne";
            assertEquals("a b c d e", HashUtil.normalizeText(input));
        }

        @Test @DisplayName("null/空白 → 空字符串")
        void nullOrBlank_returnsEmpty() {
            assertEquals("", HashUtil.normalizeText(null));
            assertEquals("", HashUtil.normalizeText(""));
            assertEquals("", HashUtil.normalizeText("   "));
        }
    }

    @Nested
    @DisplayName("HashUtil.sha256Hex 哈希计算")
    class Sha256HexTest {

        @Test @DisplayName("相同输入产生相同哈希（确定性）")
        void sameInput_sameHash() {
            String h1 = HashUtil.sha256Hex("hello world");
            String h2 = HashUtil.sha256Hex("hello world");
            assertEquals(h1, h2);
            assertEquals(64, h1.length());
        }

        @Test @DisplayName("不同输入产生不同哈希")
        void differentInput_differentHash() {
            assertNotEquals(
                    HashUtil.sha256Hex("hello"),
                    HashUtil.sha256Hex("world"));
        }

        @Test @DisplayName("输入为空时返回空字符串")
        void emptyInput_returnsEmpty() {
            assertEquals("", HashUtil.sha256Hex(null));
            assertEquals("", HashUtil.sha256Hex(""));
        }

        @Test @DisplayName("SHA-256 输出格式为 64 字符小写十六进制")
        void outputFormat_validHex() {
            String hash = HashUtil.sha256Hex("test");
            assertTrue(hash.matches("[0-9a-f]{64}"), "应为64位小写十六进制: " + hash);
        }
    }

    @Nested
    @DisplayName("HashUtil.normalizeAndHash")
    class NormalizeAndHashTest {

        @Test @DisplayName("时间戳差异 → 哈希相同（归一化后抹平）")
        void timestampDiff_sameHash() {
            String a = "正文\n更新时间：2026-06-29\n结尾";
            String b = "正文\n更新时间：2026-07-10\n结尾";
            assertEquals(HashUtil.normalizeAndHash(a), HashUtil.normalizeAndHash(b));
        }

        @Test @DisplayName("实际内容差异 → 哈希不同")
        void contentDiff_differentHash() {
            assertNotEquals(
                    HashUtil.normalizeAndHash("内容A"),
                    HashUtil.normalizeAndHash("内容B"));
        }
    }

    @Nested
    @DisplayName("HashUtil.aggregateHash 聚合哈希")
    class AggregateHashTest {

        @Test @DisplayName("多段内容聚合后稳定")
        void aggregate_isStable() {
            var list = java.util.List.of("段1", "段2", "段3");
            assertEquals(
                    HashUtil.aggregateHash(list),
                    HashUtil.aggregateHash(list));
        }

        @Test @DisplayName("空列表 → 空字符串")
        void emptyList_returnsEmpty() {
            assertEquals("", HashUtil.aggregateHash(null));
            assertEquals("", HashUtil.aggregateHash(java.util.List.of()));
        }

        @Test @DisplayName("顺序变化 → 聚合哈希变化")
        void orderChange_differentHash() {
            assertNotEquals(
                    HashUtil.aggregateHash(java.util.List.of("A", "B")),
                    HashUtil.aggregateHash(java.util.List.of("B", "A")));
        }
    }

    // ==================== ContentHashCache ====================

    @Nested
    @DisplayName("ContentHashCache 基本操作")
    class ContentHashCacheBasicTest {

        private ContentHashCache cache;

        @BeforeEach
        void setUp() {
            cache = new ContentHashCache();
        }

        @AfterEach
        void tearDown() {
            cache.clear();
        }

        @Test @DisplayName("put + get 正确存储和读取")
        void putAndGet() {
            cache.put("doc-1", "abc123");
            assertEquals("abc123", cache.get("doc-1"));
        }

        @Test @DisplayName("未 put → get 返回空字符串")
        void getUncached_returnsEmpty() {
            assertEquals("", cache.get("nonexistent"));
        }

        @Test @DisplayName("put null baseDocId → 静默忽略")
        void putNullKey_ignored() {
            assertDoesNotThrow(() -> cache.put(null, "hash"));
            assertDoesNotThrow(() -> cache.put("", "hash"));
        }

        @Test @DisplayName("put null hash → 存储空字符串")
        void putNullHash_storesEmpty() {
            cache.put("doc-2", null);
            assertEquals("", cache.get("doc-2"));
        }

        @Test @DisplayName("hasChanged：新文档返回 true")
        void hasChanged_newDoc_returnsTrue() {
            assertTrue(cache.hasChanged("new-doc", "hash123"));
        }

        @Test @DisplayName("hasChanged：相同哈希返回 false")
        void hasChanged_sameHash_returnsFalse() {
            cache.put("doc-3", "hash456");
            assertFalse(cache.hasChanged("doc-3", "hash456"));
        }

        @Test @DisplayName("hasChanged：不同哈希返回 true")
        void hasChanged_differentHash_returnsTrue() {
            cache.put("doc-4", "old-hash");
            assertTrue(cache.hasChanged("doc-4", "new-hash"));
        }

        @Test @DisplayName("hasChanged null baseDocId → 视为变更")
        void hasChangedNullKey_returnsTrue() {
            assertTrue(cache.hasChanged(null, "hash"));
        }

        @Test @DisplayName("remove 移除缓存后 hasChanged 返回 true")
        void remove_makesHasChangedTrue() {
            cache.put("doc-5", "hash");
            cache.remove("doc-5");
            assertTrue(cache.hasChanged("doc-5", "hash"));
        }

        @Test @DisplayName("needsReingest 与 hasChanged 结果一致")
        void needsReingest_matchesHasChanged() {
            cache.put("doc-6", "a");
            // 相同哈希 → 两者都返回 false
            assertFalse(cache.hasChanged("doc-6", "a"));
            assertFalse(cache.needsReingest("doc-6", "a"));
            // 不同哈希 → 两者都返回 true
            assertTrue(cache.hasChanged("doc-6", "b"));
            assertTrue(cache.needsReingest("doc-6", "b"));
            // 新文档 → 两者都返回 true
            assertTrue(cache.hasChanged("doc-7", "x"));
            assertTrue(cache.needsReingest("doc-7", "x"));
        }

        @Test @DisplayName("size 返回文档级缓存条目数")
        void size_returnsDocCount() {
            assertEquals(0, cache.size());
            cache.put("a", "1");
            cache.put("b", "2");
            assertEquals(2, cache.size());
        }
    }

    @Nested
    @DisplayName("ContentHashCache chunk API")
    class ChunkApiTest {

        private ContentHashCache cache;

        @BeforeEach
        void setUp() {
            cache = new ContentHashCache();
        }

        @Test @DisplayName("putChunk + getChunkHash")
        void putAndGetChunk() {
            cache.putChunk("doc:chunk-1", "hash1");
            assertEquals("hash1", cache.getChunkHash("doc:chunk-1"));
        }

        @Test @DisplayName("hasChunkChanged 新 chunk → true")
        void hasChunkChanged_new_returnsTrue() {
            assertTrue(cache.hasChunkChanged("new-chunk", "h"));
        }

        @Test @DisplayName("hasChunkChanged 相同哈希 → false")
        void hasChunkChanged_same_returnsFalse() {
            cache.putChunk("chunk-x", "hash-x");
            assertFalse(cache.hasChunkChanged("chunk-x", "hash-x"));
        }

        @Test @DisplayName("diffChunks 仅返回变更的 chunk")
        void diffChunks_onlyChanged() {
            cache.putChunk("c1", "old1");
            cache.putChunk("c2", "old2");

            Map<String, String> input = new LinkedHashMap<>();
            input.put("c1", "new1");  // 变更
            input.put("c2", "old2");  // 未变更
            input.put("c3", "new3");  // 新增

            Map<String, String> changed = cache.diffChunks(input);
            assertEquals(2, changed.size(), "仅 c1(变更) + c3(新增) 应返回");
            assertTrue(changed.containsKey("c1"));
            assertTrue(changed.containsKey("c3"));
            assertFalse(changed.containsKey("c2"));
        }

        @Test @DisplayName("diffChunks null 输入 → 空 Map")
        void diffChunksNull_returnsEmpty() {
            assertTrue(cache.diffChunks(null).isEmpty());
        }

        @Test @DisplayName("clear 清除所有缓存（文档+chunk）")
        void clear_removesAll() {
            cache.put("doc", "h");
            cache.putChunk("chk", "h");
            cache.clear();
            assertEquals("", cache.get("doc"));
            assertEquals("", cache.getChunkHash("chk"));
            assertEquals(0, cache.size());
        }
    }
}
