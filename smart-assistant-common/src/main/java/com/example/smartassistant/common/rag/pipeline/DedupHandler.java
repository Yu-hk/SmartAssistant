/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 检索结果去重 Handler。
 *
 * <p>参考 Snail AI 的 DedupStrategy 设计。
 * 对 Pipeline 检索结果做内容去重，移除：
 * <ul>
 *   <li>完全重复的内容（精确去重）</li>
 *   <li>高度相似的内容（基于文本重叠度，去除语义冗余）</li>
 * </ul>
 *
 * <p>在 Pipeline 中 Order=105，在 RrfFusionHandler (Order=100) 之后、
 * RerankHandler (Order=110) 之前执行。
 */
public class DedupHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(DedupHandler.class);

    /** 去重模式 */
    public enum DedupMode {
        /** 仅精确去重（SHA-256 完全匹配） */
        EXACT,
        /** 精确 + 模糊去重（内容重叠度 > threshold） */
        AGGRESSIVE
    }

    /** 默认模糊去重阈值（内容重叠度 > 85% 视为重复） */
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.85;

    private final boolean enabled;
    private final DedupMode mode;
    private final double similarityThreshold;

    public DedupHandler(boolean enabled, DedupMode mode, double similarityThreshold) {
        this.enabled = enabled;
        this.mode = mode;
        this.similarityThreshold = similarityThreshold > 0 ? similarityThreshold : DEFAULT_SIMILARITY_THRESHOLD;
    }

    public DedupHandler() {
        this(true, DedupMode.AGGRESSIVE, DEFAULT_SIMILARITY_THRESHOLD);
    }

    @Override
    public void handle(RagSearchContext context) {
        if (!enabled) return;

        List<RagSearchContext.RankedItem> fused = context.getFusedResults();
        if (fused == null || fused.size() <= 1) return;

        int before = fused.size();
        long start = System.currentTimeMillis();

        List<RagSearchContext.RankedItem> deduped;

        if (mode == DedupMode.EXACT) {
            deduped = exactDedup(fused);
        } else {
            deduped = aggressiveDedup(fused);
        }

        context.setFusedResults(deduped);

        int removed = before - deduped.size();
        if (removed > 0) {
            log.info("[DedupHandler] 去重完成: {}→{} (移除 {}), 耗时={}ms, mode={}",
                    before, deduped.size(), removed,
                    System.currentTimeMillis() - start, mode);
        }
    }

    /**
     * 精确去重：基于 SHA-256 内容哈希。
     */
    private List<RagSearchContext.RankedItem> exactDedup(
            List<RagSearchContext.RankedItem> items) {
        Set<String> seen = new HashSet<>();
        List<RagSearchContext.RankedItem> result = new ArrayList<>();

        for (RagSearchContext.RankedItem item : items) {
            String hash = sha256(item.getContent());
            if (!seen.contains(hash)) {
                seen.add(hash);
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 模糊去重：精确去重 + 内容重叠度检测。
     *
     * <p>对每两个结果计算 Jaccard 字符重叠度，
     * 如果重叠度 > threshold，保留 RRF 分数更高者。
     */
    private List<RagSearchContext.RankedItem> aggressiveDedup(
            List<RagSearchContext.RankedItem> items) {
        // 先做精确去重
        List<RagSearchContext.RankedItem> exactDeduped = exactDedup(items);
        if (exactDeduped.size() <= 1) return exactDeduped;

        List<RagSearchContext.RankedItem> result = new ArrayList<>();
        for (RagSearchContext.RankedItem item : exactDeduped) {
            boolean isDuplicate = false;
            for (RagSearchContext.RankedItem existing : result) {
                double similarity = computeContentOverlap(
                        item.getContent(), existing.getContent());
                if (similarity >= similarityThreshold) {
                    // 保留 RRF 分数更高的
                    isDuplicate = true;
                    log.trace("[DedupHandler] 模糊去重: similarity={}, keeping higher score",
                            String.format("%.2f", similarity));
                    break;
                }
            }
            if (!isDuplicate) {
                result.add(item);
            }
        }

        return result;
    }

    /**
     * 计算两个文本的内容重叠度（Jaccard 相似度，基于字符 n-gram）。
     */
    static double computeContentOverlap(String a, String b) {
        if (a == null || b == null) return 0;
        if (a.equals(b)) return 1.0;

        // 使用字符 3-gram 计算重叠
        Set<String> ngramsA = extractCharNgrams(a, 3);
        Set<String> ngramsB = extractCharNgrams(b, 3);

        if (ngramsA.isEmpty() && ngramsB.isEmpty()) return 1.0;
        if (ngramsA.isEmpty() || ngramsB.isEmpty()) return 0.0;

        // Jaccard = |A ∩ B| / |A ∪ B|
        Set<String> intersection = new HashSet<>(ngramsA);
        intersection.retainAll(ngramsB);

        Set<String> union = new HashSet<>(ngramsA);
        union.addAll(ngramsB);

        return (double) intersection.size() / union.size();
    }

    /**
     * 提取字符 n-gram。
     */
    private static Set<String> extractCharNgrams(String text, int n) {
        if (text == null || text.length() < n) {
            return text != null ? Set.of(text) : Set.of();
        }
        Set<String> ngrams = new HashSet<>();
        for (int i = 0; i <= text.length() - n; i++) {
            ngrams.add(text.substring(i, i + n));
        }
        return ngrams;
    }

    /**
     * 计算 SHA-256 哈希。
     */
    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(
                    (text != null ? text : "").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // fallback: 使用 hashCode
            return String.valueOf(text != null ? text.hashCode() : 0);
        }
    }

    @Override
    public int getOrder() {
        return 105; // 在 RrfFusionHandler (Order=100) 之后，RerankHandler (Order=110) 之前
    }
}
