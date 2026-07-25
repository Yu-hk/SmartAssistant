/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.chunking;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * 基于 embedding 的语义分块策略（Kamradt 式 breakpoint 检测）。
 * <p>
 * 将文本按句切分 → 逐句 embedding → 计算相邻句余弦相似度 →
 * 相似度骤降处（低于 rolling 分位阈值）即为语义边界 → 聚合成块。
 * 用于弥补规则分块在「无结构长文 / OCR 流水 / 图片描述」上切断语义的缺陷。
 * </p>
 *
 * <p>安全措施（对标文章对语义切分的三条硬警告）：</p>
 * <ul>
 *   <li>{@code minChunkTokens} 护栏（默认 256）：避免超小碎块（文章指出平均 43 Token 会上下文不足）；</li>
 *   <li>{@code hardMaxTokens} 硬上限（= 调用方传入的 maxTokens）：单块不超限；</li>
 *   <li><b>embedder 不可用 / 任意异常 → 自动降级 {@link RecursiveChunkStrategy}</b>：
 *       不依赖 BGE 服务可用性，契合项目 Resilient 容错哲学。</li>
 * </ul>
 *
 * <p>可注入 {@link Function} 形式的 embedder（测试用确定性 stub；生产用 {@link BgeEmbeddingModel}）。</p>
 */
public class EmbeddingSemanticChunkStrategy implements ChunkStrategy {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingSemanticChunkStrategy.class);

    /** 可注入的 embedder（默认用 BgeEmbeddingModel.embedding）；为 null 时直接降级规则分块 */
    private final Function<String, float[]> embedder;

    /** 规则分块兜底 */
    private final RecursiveChunkStrategy fallback;

    /** breakpoint 分位阈值（相邻相似度的下分位，低于则切分） */
    private final double breakpointQuantile;

    /** 最小块 token 数护栏（避免超小碎块，文章建议 200-400） */
    private final int minChunkTokens;

    public EmbeddingSemanticChunkStrategy(BgeEmbeddingModel bge) {
        this(bge != null ? bge::embedding : null,
                new RecursiveChunkStrategy(), 0.85, 256);
    }

    public EmbeddingSemanticChunkStrategy(Function<String, float[]> embedder,
                                           RecursiveChunkStrategy fallback,
                                           double breakpointQuantile,
                                           int minChunkTokens) {
        this.embedder = embedder;
        this.fallback = fallback != null ? fallback : new RecursiveChunkStrategy();
        this.breakpointQuantile = breakpointQuantile;
        this.minChunkTokens = minChunkTokens;
    }

    @Override
    public List<Chunk> chunk(String text, int maxTokens, int overlap) {
        if (text == null || text.isBlank()) return List.of();
        int hardMax = maxTokens > 0 ? maxTokens : 1024;
        if (embedder == null) {
            log.debug("[EmbeddingSemantic] embedder 为空，降级规则分块");
            return fallback.chunk(text, maxTokens, overlap);
        }
        try {
            // 1. 切句
            List<String> sentences = splitSentences(text);
            if (sentences.size() <= 1) {
                return fallback.chunk(text, maxTokens, overlap);
            }
            // 2. 逐句 embedding（任一条失败 → 降级）
            float[][] vecs = new float[sentences.size()][];
            for (int i = 0; i < sentences.size(); i++) {
                float[] v = embedder.apply(sentences.get(i));
                if (v == null) {
                    log.debug("[EmbeddingSemantic] 第{}句 embedding 返回 null，降级规则分块", i);
                    return fallback.chunk(text, maxTokens, overlap);
                }
                vecs[i] = v;
            }
            // 3. 相邻余弦相似度
            double[] sim = new double[sentences.size() - 1];
            for (int i = 0; i < sim.length; i++) {
                sim[i] = cosine(vecs[i], vecs[i + 1]);
            }
            // 4. rolling 分位找 breakpoint 阈值
            double threshold = quantile(sim, breakpointQuantile);
            // 5. 按边界聚合 + minChunk 护栏 + hardMax 截断
            List<String> groups = merge(sentences, sim, threshold, hardMax);
            // 6. 转 Chunk
            List<Chunk> result = new ArrayList<>();
            int idx = 0;
            for (String g : groups) {
                g = g.trim();
                if (g.isEmpty()) continue;
                result.add(new Chunk(g, idx++, RecursiveChunkStrategy.estimateTokens(g), ""));
            }
            log.debug("[EmbeddingSemantic] 语义分块: sents={}, groups={}, threshold={.3f}",
                    sentences.size(), result.size(), threshold);
            return result;
        } catch (Exception e) {
            log.warn("[EmbeddingSemantic] 语义分块异常，降级规则分块: {}", e.getMessage());
            return fallback.chunk(text, maxTokens, overlap);
        }
    }

    /** 中英文通用切句：句号/叹号/问号/分号/换行 作为句界 */
    private List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            sb.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '；'
                    || c == '.' || c == '!' || c == '?' || c == ';'
                    || c == '\n') {
                if (sb.length() > 0) {
                    out.add(sb.toString().trim());
                    sb.setLength(0);
                }
            }
        }
        if (sb.length() > 0) out.add(sb.toString().trim());
        out.removeIf(String::isBlank);
        return out;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 对数组排序取 quantile 分位值 */
    private static double quantile(double[] arr, double q) {
        if (arr.length == 0) return 0;
        double[] sorted = Arrays.copyOf(arr, arr.length);
        Arrays.sort(sorted);
        int idx = (int) Math.max(0, Math.min(sorted.length - 1, Math.floor(q * (sorted.length - 1))));
        return sorted[idx];
    }

    /**
     * 按 breakpoint 聚合句子，并施加护栏。
     * <ol>
     *   <li>阶段1：相邻相似度 < threshold 处切分为原始组；</li>
     *   <li>阶段2：minChunk 护栏——过小的组并入前一组（不超 hardMax 时）；</li>
     *   <li>阶段2：超长组（罕见，单段超 hardMax）用 fallback 规则切。</li>
     * </ol>
     */
    private List<String> merge(List<String> sentences, double[] sim, double threshold, int hardMax) {
        // 阶段1：按 breakpoint 切原始组
        List<String> raw = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            if (cur.length() > 0) cur.append(" ");
            cur.append(sentences.get(i));
            boolean boundary = (i < sim.length) && (sim[i] < threshold);
            if (boundary) {
                raw.add(cur.toString().trim());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) raw.add(cur.toString().trim());

        // 阶段2：护栏 + 硬上限
        List<String> groups = new ArrayList<>();
        for (String g : raw) {
            int gTok = RecursiveChunkStrategy.estimateTokens(g);
            // minChunk 护栏：过小且可并入前组（不超 hardMax）→ 并入
            if (gTok < minChunkTokens && !groups.isEmpty()) {
                int prevTok = RecursiveChunkStrategy.estimateTokens(groups.get(groups.size() - 1));
                if (prevTok + gTok <= hardMax) {
                    groups.set(groups.size() - 1, groups.get(groups.size() - 1) + " " + g);
                    continue;
                }
            }
            // 超长组：fallback 规则切（极少触发）
            if (gTok > hardMax) {
                for (Chunk c : fallback.chunk(g, hardMax, 0)) {
                    groups.add(c.getText());
                }
                continue;
            }
            groups.add(g);
        }
        return groups;
    }
}
