/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticChunkStrategyTest {

    @Test
    void overlapIsAppliedWithoutExceedingMaxTokens() {
        String text = "# 第一章\n" + "甲".repeat(180)
                + "\n# 第二章\n" + "乙".repeat(180);

        List<Chunk> chunks = new SemanticChunkStrategy().chunk(text, 100, 20);

        assertTrue(chunks.size() > 2);
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            String fullContent = chunk.getPrefix() + chunk.getText();
            assertTrue(RecursiveChunkStrategy.estimateTokens(fullContent) <= 100,
                    "重叠后的块不能超过 maxTokens");
            if (i == 0) {
                assertEquals("", chunk.getPrefix());
            } else {
                assertFalse(chunk.getPrefix().isEmpty(), "后续块应带有上一块尾部重叠前缀");
                String previous = chunks.get(i - 1).getPrefix() + chunks.get(i - 1).getText();
                assertTrue(previous.endsWith(chunk.getPrefix()), "前缀应来自上一块尾部");
            }
        }
    }

    @Test
    void zeroOverlapKeepsPrefixesEmpty() {
        String text = "# 第一章\n" + "甲".repeat(120)
                + "\n# 第二章\n" + "乙".repeat(120);

        List<Chunk> chunks = new SemanticChunkStrategy().chunk(text, 100, 0);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getPrefix().isEmpty()));
    }
}
