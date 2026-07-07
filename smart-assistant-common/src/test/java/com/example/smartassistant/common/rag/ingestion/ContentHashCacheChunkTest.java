/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContentHashCache chunk 级增量 diff 测试（P2-a）。
 */
class ContentHashCacheChunkTest {

    @Test
    void freshChunkIsAlwaysChanged() {
        ContentHashCache cache = new ContentHashCache();
        assertTrue(cache.hasChunkChanged("file-p1-s1", "h1"),
                "首次摄入的 chunk 应判定为已变更");
    }

    @Test
    void unchangedChunkIsSkipped() {
        ContentHashCache cache = new ContentHashCache();
        cache.putChunk("file-p1-s1", "h1");
        assertFalse(cache.hasChunkChanged("file-p1-s1", "h1"),
                "相同哈希的 chunk 应判定为未变更");
        assertTrue(cache.hasChunkChanged("file-p1-s1", "h2"),
                "不同哈希的 chunk 应判定为已变更");
    }

    @Test
    void diffChunksReturnsOnlyChanged() {
        ContentHashCache cache = new ContentHashCache();
        cache.putChunk("c1", "h1");
        cache.putChunk("c2", "h2");

        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("c1", "h1"); // 未变
        incoming.put("c2", "h2-changed"); // 变
        incoming.put("c3", "h3"); // 新

        Map<String, String> changed = cache.diffChunks(incoming);
        assertEquals(2, changed.size(), "应报告 2 个变更 chunk");
        assertTrue(changed.containsKey("c2"));
        assertTrue(changed.containsKey("c3"));
        assertFalse(changed.containsKey("c1"), "未变更 chunk 不应出现在结果中");
    }

    @Test
    void removeClearsChunkEntries() {
        ContentHashCache cache = new ContentHashCache();
        cache.putChunk("file-p1-s1", "h1");
        cache.remove("file-p1-s1");
        assertTrue(cache.hasChunkChanged("file-p1-s1", "h1"),
                "remove 后该 chunk 应回到未缓存状态（视为变更）");
    }

    @Test
    void clearEmptiesChunkCache() {
        ContentHashCache cache = new ContentHashCache();
        cache.putChunk("c1", "h1");
        cache.clear();
        assertTrue(cache.hasChunkChanged("c1", "h1"));
    }
}
