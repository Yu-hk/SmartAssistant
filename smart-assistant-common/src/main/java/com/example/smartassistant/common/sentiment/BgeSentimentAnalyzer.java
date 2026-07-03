/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.sentiment;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ⭐ BGE 语义情感分析 — 使用 BGE 向量模型识别用户情绪。
 * <p>
 * 对每个情感等级预置一组"种子文本"（典型表达），
 * 计算其 BGE 向量并缓存。分析时将用户输入转换为向量，
 * 与各等级种子向量做余弦相似度，取最匹配的等级。
 * </p>
 *
 * <p>与关键词匹配互补：关键词处理明确情绪词，BGE 处理语义相似但字面不同的表达。</p>
 */
public class BgeSentimentAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(BgeSentimentAnalyzer.class);

    /** 各情感等级的种子文本 */
    private static final Map<Integer, List<String>> SEED_TEXTS = Map.of(
            1, List.of(
                    "谢谢你的帮助", "非常满意", "服务很好", "太棒了",
                    "效率很高", "非常感谢", "赞", "用户体验很好",
                    "你们做得很好", "推荐给朋友", "好评"
            ),
            2, List.of(
                    "你好", "请问", "我想查询一下", "咨询一个问题",
                    "帮我看看", "在吗", "你好请问", "了解一下",
                    "我想问一下", "有个问题"
            ),
            3, List.of(
                    "有点慢", "不太方便", "一般般", "感觉不太好",
                    "不太满意", "有点麻烦", "能不能快点", "等了一下",
                    "不太舒服", "有点失望", "还可以吧"
            ),
            4, List.of(
                    "太慢了", "等了很久", "很不满意", "差劲",
                    "太差了吧", "服务太差了", "非常失望", "太糟糕了",
                    "怎么这么慢", "太离谱了", "受不了了"
            ),
            5, List.of(
                    "我要投诉", "你们太垃圾了", "骗子", "赔偿损失",
                    "举报你们", "太过分了", "无法容忍", "必须给个说法",
                    "你们这是欺诈", "等着收律师函吧", "曝光你们"
            )
    );

    /** 余弦相似度阈值 — 低于此值不采用 BGE 结果 */
    private static final double MIN_SIMILARITY = 0.35;

    /** 各等级种子文本的向量缓存（等级 → 向量列表） */
    private final Map<Integer, List<float[]>> seedVectors = new ConcurrentHashMap<>();

    /** BGE 嵌入模型 */
    private final BgeEmbeddingModel embeddingModel;

    /** 是否初始化完成 */
    private volatile boolean initialized = false;

    public BgeSentimentAnalyzer(BgeEmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 初始化种子向量（首次使用时懒加载）。
     */
    private void ensureInitialized() {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            if (embeddingModel == null || !embeddingModel.isAvailable()) {
                log.warn("[BgeSentiment] BGE 模型不可用，跳过语义情感分析");
                initialized = true;
                return;
            }
            int totalSeeds = 0;
            for (var entry : SEED_TEXTS.entrySet()) {
                int level = entry.getKey();
                List<float[]> vectors = new ArrayList<>();
                for (String seed : entry.getValue()) {
                    float[] vec = embeddingModel.embedding(seed);
                    if (vec != null) {
                        vectors.add(normalize(vec));
                        totalSeeds++;
                    }
                }
                if (!vectors.isEmpty()) {
                    seedVectors.put(level, vectors);
                }
            }
            initialized = true;
            log.info("[BgeSentiment] 初始化完成: {} 个等级, {} 条种子向量", seedVectors.size(), totalSeeds);
        }
    }

    /**
     * 分析用户输入的情感等级（基于 BGE 语义相似度）。
     *
     * @param userInput 用户输入文本
     * @return 匹配的情感等级（1~5），0 表示无法确定
     */
    public int analyze(String userInput) {
        if (userInput == null || userInput.isBlank()) return 0;
        ensureInitialized();

        if (seedVectors.isEmpty()) return 0;

        // 计算用户输入的 embedding
        float[] inputVec = embeddingModel.embedding(userInput);
        if (inputVec == null) return 0;
        inputVec = normalize(inputVec);

        // 遍历各等级种子，找最高相似度
        int bestLevel = 0;
        double bestScore = 0;

        for (var entry : seedVectors.entrySet()) {
            int level = entry.getKey();
            for (float[] seedVec : entry.getValue()) {
                double sim = cosineSimilarity(inputVec, seedVec);
                if (sim > bestScore) {
                    bestScore = sim;
                    bestLevel = level;
                }
            }
        }

        if (bestScore < MIN_SIMILARITY) {
            log.debug("[BgeSentiment] 未匹配到足够相似的情感: bestScore={}", String.format("%.4f", bestScore));
            return 0;
        }

        log.debug("[BgeSentiment] 匹配情感等级: level={}, score={}", bestLevel, String.format("%.4f", bestScore));
        return bestLevel;
    }

    // ==================== 向量工具 ====================

    private static float[] normalize(float[] vec) {
        double norm = 0;
        for (float v : vec) norm += (double) v * v;
        norm = Math.sqrt(norm);
        if (norm == 0) return vec;
        float[] result = new float[vec.length];
        for (int i = 0; i < vec.length; i++) result[i] = (float) (vec[i] / norm);
        return result;
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }
}
