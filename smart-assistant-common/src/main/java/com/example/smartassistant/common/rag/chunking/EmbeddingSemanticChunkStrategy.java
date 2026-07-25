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
import java.util.Collections;
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
 * <p>边界重叠控制：当调用方传入 {@code overlap>0} 时，在相邻语义块边界把上一块尾部
 * （≤ overlap token，且不超过上块一半，避免「重叠比正文还长」）按句子粒度复制到当前块头部，
 * 作为 {@link Chunk#getPrefix()} 承载；内容与 {@link RecursiveChunkStrategy#applyOverlap} 一致
 * （复制不移动），缓解 breakpoint 硬切导致的边界上下文丢失。</p>
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
            // 5. 按边界聚合 + minChunk 护栏 + hardMax 截断（返回句子组结构，便于句子级重叠）
            //    oversizedPrefixes 与 groups 对齐：超长组（单语义组超 hardMax）由规则递归切分，
            //    其递归产出的重叠前缀需原样保留，不可被句子级 applyOverlap 覆盖丢弃。
            List<String> oversizedPrefixes = new ArrayList<>();
            List<List<String>> groups = merge(sentences, sim, threshold, hardMax, overlap, oversizedPrefixes);
            // 6. 边界重叠控制（仅 overlap>0 时生效；与 RecursiveChunkStrategy 语义一致：复制不移动）
            List<String> prefixes = applyOverlap(groups, overlap);
            // 7. 转 Chunk（prefix 承载重叠文本，内容 = prefix + text 仅一份重叠）
            List<Chunk> result = new ArrayList<>();
            int idx = 0;
            for (int i = 0; i < groups.size(); i++) {
                String g = String.join(" ", groups.get(i)).trim();
                if (g.isEmpty()) continue;
                // 超长组来源：沿用递归切分已算好的重叠前缀；其余：用句子级 breakpoint 重叠
                String prefix = (!oversizedPrefixes.isEmpty() && !oversizedPrefixes.get(i).isEmpty())
                        ? oversizedPrefixes.get(i) : prefixes.get(i);
                result.add(new Chunk(g, idx++, RecursiveChunkStrategy.estimateTokens(g),
                        prefix != null ? prefix : ""));
            }
            log.debug("[EmbeddingSemantic] 语义分块: sents={}, groups={}, threshold={.3f}, overlap={}",
                    sentences.size(), result.size(), threshold, overlap);
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
     * 按 breakpoint 聚合句子，并施加护栏。返回「句子组」结构（每个元素是一组句子列表），
     * 以便后续按句子粒度施加边界重叠。
     * <ol>
     *   <li>阶段1：相邻相似度 < threshold 处切分为原始组；</li>
     *   <li>阶段2：minChunk 护栏——过小的组并入前一组（不超 hardMax 时）；</li>
     *   <li>阶段2：超长组（罕见，单段超 hardMax）用 fallback 规则切。</li>
     * </ol>
     */
    private List<List<String>> merge(List<String> sentences, double[] sim, double threshold,
                                   int hardMax, int overlap, List<String> oversizedPrefixes) {
        // 阶段1：按 breakpoint 切原始组（每组 = 句子列表）
        List<List<String>> raw = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            cur.add(sentences.get(i));
            boolean boundary = (i < sim.length) && (sim[i] < threshold);
            if (boundary) {
                raw.add(new ArrayList<>(cur));
                cur = new ArrayList<>();
            }
        }
        if (!cur.isEmpty()) raw.add(cur);

        // 阶段2：护栏 + 硬上限
        List<List<String>> groups = new ArrayList<>();
        for (List<String> g : raw) {
            int gTok = estimateGroupTokens(g);
            // minChunk 护栏：过小且可并入前组（不超 hardMax）→ 并入
            if (gTok < minChunkTokens && !groups.isEmpty()) {
                List<String> prev = groups.get(groups.size() - 1);
                int prevTok = estimateGroupTokens(prev);
                if (prevTok + gTok <= hardMax) {
                    prev.addAll(g);
                    continue;
                }
            }
            // 超长组：fallback 规则切（极少触发）。⭐ 传入 overlap（此前硬编码 0），
            // 使单组超 hardMax 时子级递归切分同样携带边界重叠，避免子块语义被硬切。
            if (gTok > hardMax) {
                StringBuilder sb = new StringBuilder();
                for (String s : g) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(s);
                }
                // ⭐ 保留递归切分产出的重叠前缀：把每个递归子块整体作为一个组，
                // 其 prefix（上一子块尾部）原样记入 oversizedPrefixes，避免被句子级 applyOverlap 覆盖丢弃。
                for (Chunk c : fallback.chunk(sb.toString(), hardMax, overlap)) {
                    groups.add(List.of(c.getText()));
                    oversizedPrefixes.add(c.getPrefix());
                }
                continue;
            }
            groups.add(g);
            oversizedPrefixes.add("");
        }
        return groups;
    }

    /** 句子组的 token 估算（拼接后估算，避免重复 join 开销由调用方视情况使用） */
    private static int estimateGroupTokens(List<String> group) {
        StringBuilder sb = new StringBuilder();
        for (String s : group) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(s);
        }
        return RecursiveChunkStrategy.estimateTokens(sb.toString());
    }

    /**
     * 边界重叠控制（仅 overlap>0 生效）：把上一组的尾部若干句子（累计 token ≤ overlap，
     * 且不超过上组一半）复制到当前组头部，作为 prefix 写入当前块。
     * <p>与 {@link RecursiveChunkStrategy#applyOverlap} 语义一致：复制不移动，上一组内容不变。</p>
     *
     * @return 与 groups 对齐的 prefix 列表（groups[i] 对应的重叠前缀文本，无重叠则为空串）
     */
    private List<String> applyOverlap(List<List<String>> groups, int overlap) {
        List<String> prefixes = new ArrayList<>(Collections.nCopies(groups.size(), ""));
        if (overlap <= 0 || groups.size() <= 1) return prefixes;
        for (int i = 1; i < groups.size(); i++) {
            List<String> prev = groups.get(i - 1);
            if (prev.isEmpty()) continue;
            int prevTok = estimateGroupTokens(prev);
            // 重叠预算：不超过调用方指定 overlap，且不超过上组一半（防止「重叠比正文还长」）
            int budget = Math.min(overlap, prevTok / 2);
            List<String> tail = new ArrayList<>();
            int acc = 0;
            for (int j = prev.size() - 1; j >= 0 && acc < budget; j--) {
                int t = RecursiveChunkStrategy.estimateTokens(prev.get(j));
                if (acc + t > budget) break;
                tail.add(0, prev.get(j));
                acc += t;
            }
            if (!tail.isEmpty()) {
                prefixes.set(i, String.join(" ", tail));
            }
        }
        return prefixes;
    }
}
