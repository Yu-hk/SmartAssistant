/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.chunking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RecursiveChunkStrategy 单元测试：重点锁定「边界重叠不双计」。
 * 调用方（ParentChildDocumentChunker / DocumentChunker）统一以 {@code prefix + text} 组装内容，
 * 因此重叠文本只能存在于 prefix，不可同时 prepend 进 text，否则会被计入两次。
 */
class RecursiveChunkStrategyTest {

    @Test
    @DisplayName("applyOverlap 不双计：prefix=上一块尾部，text 不含重叠，组合内容仅出现一次")
    void applyOverlapDoesNotDoubleCount() {
        RecursiveChunkStrategy strategy = new RecursiveChunkStrategy();
        String text = "第一段技术内容描述系统架构设计。第二段介绍核心模块职责划分。"
                + "第三段说明数据流转与接口契约。第四段阐述容错与降级策略。";
        List<Chunk> chunks = strategy.chunk(text, 30, 12);
        assertTrue(chunks.size() >= 2, "文本应被切成多个块，实际=" + chunks.size());

        for (int i = 1; i < chunks.size(); i++) {
            Chunk cur = chunks.get(i);
            Chunk prev = chunks.get(i - 1);
            String prefix = cur.getPrefix();
            assertFalse(prefix.isEmpty(), "非首块应携带重叠 prefix");

            // 1. prefix 必须是上一块文本的尾部（复制不移动）
            assertTrue(prev.getText().endsWith(prefix.trim()),
                    "prefix 应为上一块尾部: prefix=" + prefix + " | prevTail=" + prev.getText());

            // 2. text 本身不应已包含该重叠（否则与调用方 prefix+text 组装会双计）
            assertFalse(cur.getText().startsWith(prefix.trim()),
                    "text 不应已包含重叠前缀（避免 double-count）");

            // 3. 组合内容中该重叠仅出现一次（位于开头）
            String combined = cur.getPrefix() + cur.getText();
            int first = combined.indexOf(prefix.trim());
            int last = combined.lastIndexOf(prefix.trim());
            assertEquals(first, last, "重叠文本在组合内容中应仅出现一次");
            assertEquals(0, first, "重叠前缀应位于组合内容开头");
        }
    }

    @Test
    @DisplayName("overlap=0 时不产生任何重叠 prefix")
    void noOverlapWhenZero() {
        RecursiveChunkStrategy strategy = new RecursiveChunkStrategy();
        String text = "第一段内容。第二段内容。第三段内容。第四段内容。第五段内容。";
        List<Chunk> chunks = strategy.chunk(text, 30, 0);
        for (Chunk c : chunks) {
            assertTrue(c.getPrefix().isEmpty(), "overlap=0 时不应携带 prefix");
        }
    }
}
