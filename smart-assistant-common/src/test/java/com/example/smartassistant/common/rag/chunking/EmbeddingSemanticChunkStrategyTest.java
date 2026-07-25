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
}
