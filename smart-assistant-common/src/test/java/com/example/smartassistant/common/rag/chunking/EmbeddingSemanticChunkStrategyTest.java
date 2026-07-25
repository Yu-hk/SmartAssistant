/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EmbeddingSemanticChunkStrategy 测试：BGE 分支切分、minChunk 护栏、embedder 不可用降级。
 * 使用确定性 stub embedder（字符 trigram 频率向量），不依赖真实 BGE 服务。
 */
class EmbeddingSemanticChunkStrategyTest {

    /** 字符 trigram 频率向量（64 维 L2 归一化）——确定且同主题句子向量近、异主题远 */
    private static Function<String, float[]> stubEmbedder() {
        return sentence -> {
            String s = sentence.replaceAll("\\s+", "");
            int dim = 64;
            float[] v = new float[dim];
            for (int i = 0; i + 2 < s.length(); i++) {
                int idx = Math.floorMod(s.substring(i, i + 3).hashCode(), dim);
                v[idx] += 1;
            }
            double n = 0;
            for (float x : v) n += x * x;
            n = Math.sqrt(n);
            if (n > 0) for (int i = 0; i < dim; i++) v[i] /= (float) n;
            return v;
        };
    }

    @Test
    void bgeBranchSplitsBySemanticBoundary() {
        // 前半「订单/退款」主题，后半「天气」主题，中间语义骤降应为 breakpoint
        String text = "订单已提交成功请耐心等待发货。退款申请将在三个工作日内审核通过。"
                + "客服热线是四零零一二三。物流状态显示为已签收请注意查收。"
                + "今日天气晴转多云气温适宜。紫外线指数中等外出建议涂抹防晒。"
                + "周末适合骑行和徒步运动。傍晚可能有阵雨请携带雨具。";
        EmbeddingSemanticChunkStrategy strategy =
                new EmbeddingSemanticChunkStrategy(stubEmbedder(), new RecursiveChunkStrategy(), 0.85, 10);
        List<Chunk> chunks = strategy.chunk(text, 1024, 0);
        assertTrue(chunks.size() >= 2, "语义边界应切出多个块，实际=" + chunks.size());
        for (Chunk c : chunks) {
            assertTrue(RecursiveChunkStrategy.estimateTokens(c.getText()) >= 10,
                    "受 minChunk 护栏约束不应产生超小碎块: " + c.getText());
        }
    }

    @Test
    void minChunkGuardMergesTinySameTopicGroups() {
        // 同主题短句（共享字符 trigram → 相似度高 → 无 breakpoint）；每句很小 → 应被合并
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("订单退款申请已处理完成请查收。");
        }
        EmbeddingSemanticChunkStrategy strategy =
                new EmbeddingSemanticChunkStrategy(stubEmbedder(), new RecursiveChunkStrategy(), 0.85, 50);
        List<Chunk> chunks = strategy.chunk(sb.toString(), 1024, 0);
        assertEquals(1, chunks.size(), "同主题微小句应被 minChunk 护栏合并为单块，实际=" + chunks.size());
    }

    @Test
    void fallbackWhenEmbedderNull() {
        EmbeddingSemanticChunkStrategy strategy =
                new EmbeddingSemanticChunkStrategy(null, new RecursiveChunkStrategy(), 0.85, 256);
        String text = "第一句内容。第二句内容。第三句内容。";
        List<Chunk> chunks = strategy.chunk(text, 1024, 0);
        assertFalse(chunks.isEmpty(), "embedder 为 null 时应降级规则分块且非空");
    }

    @Test
    void overlapCarriesPrevTailIntoNextChunkWithoutDuplicating() {
        // 长无结构文本（同主题字符 trigram → 无 breakpoint → 单组），但用较大 hardMax 触发多组：
        // 构造「前半订单主题 + 后半天气主题」以产生 ≥2 个语义组，overlap=50 应把上一组尾部带入下一组 prefix
        String text = "订单已提交成功请耐心等待发货。退款申请将在三个工作日内审核通过。"
                + "客服热线是四零零一二三。物流状态显示为已签收请注意查收。"
                + "今日天气晴转多云气温适宜。紫外线指数中等外出建议涂抹防晒。"
                + "周末适合骑行和徒步运动。傍晚可能有阵雨请携带雨具。";
        EmbeddingSemanticChunkStrategy strategy =
                new EmbeddingSemanticChunkStrategy(stubEmbedder(), new RecursiveChunkStrategy(), 0.85, 10);
        List<Chunk> chunks = strategy.chunk(text, 1024, 50);
        assertTrue(chunks.size() >= 2, "应切出多个语义块");

        // 找到带 prefix 的块（非首块），其 prefix 应等于上一块内容的尾部子串
        boolean foundOverlap = false;
        for (int i = 1; i < chunks.size(); i++) {
            Chunk cur = chunks.get(i);
            Chunk prev = chunks.get(i - 1);
            if (!cur.getPrefix().isEmpty()) {
                foundOverlap = true;
                // prefix 必须是上一块文本的尾部（复制不移动）
                assertTrue(prev.getText().endsWith(cur.getPrefix().trim()),
                        "prefix 应为上一块尾部: prefix=" + cur.getPrefix() + " | prevTail=" + prev.getText());
                // 内容 = prefix + text 仅一份重叠，不应出现 prefix 被重复两次
                String content = cur.getPrefix() + cur.getText();
                int firstIdx = content.indexOf(cur.getPrefix().trim());
                int lastIdx = content.lastIndexOf(cur.getPrefix().trim());
                assertEquals(firstIdx, lastIdx,
                        "重叠文本不应在内容中出现两次（避免 double-count）");
            }
        }
        assertTrue(foundOverlap, "overlap>0 时应至少有一个块携带 prefix 重叠文本");
    }

    @Test
    void noOverlapWhenOverlapZero() {
        String text = "订单已提交成功请耐心等待发货。退款申请将在三个工作日内审核通过。"
                + "今日天气晴转多云气温适宜。周末适合骑行和徒步运动。";
        EmbeddingSemanticChunkStrategy strategy =
                new EmbeddingSemanticChunkStrategy(stubEmbedder(), new RecursiveChunkStrategy(), 0.85, 10);
        List<Chunk> chunks = strategy.chunk(text, 1024, 0);
        for (Chunk c : chunks) {
            assertTrue(c.getPrefix().isEmpty(), "overlap=0 时不应有任何 prefix 重叠");
        }
    }
}
