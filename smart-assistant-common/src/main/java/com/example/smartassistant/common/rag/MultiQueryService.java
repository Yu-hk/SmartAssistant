/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ⭐ Multi-Query 查询扩展服务 — 将用户问题生成多个角度变体，提升召回覆盖率。
 * <p>
 * 对应 RAG 文章"查询层"的 Multi-Query 策略：
 * 一个问题可能有多种说法，生成 3-5 个不同角度分别检索，再合并去重。
 * </p>
 *
 * <p>不依赖 Spring AI，通过 {@link Function}{@code <String, String>} 注入 LLM 调用能力。</p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 在 Router 模块中：
 * MultiQueryService mq = new MultiQueryService(
 *     prompt -> chatClient.prompt().user(prompt).call().content()
 * ).withCount(3);
 * List<String> variants = mq.generate("iPhone 15 Pro Max 为什么这么贵");
 * }</pre>
 */
public class MultiQueryService {

    private static final Logger log = LoggerFactory.getLogger(MultiQueryService.class);

    /** LLM 调用接口：接收 prompt，返回 LLM 回复 */
    private final Function<String, String> llmCall;

    /** 生成变体数量 */
    private int variantCount = 3;

    public MultiQueryService(Function<String, String> llmCall) {
        this.llmCall = llmCall;
    }

    // ==================== 配置方法 ====================

    public MultiQueryService withCount(int count) {
        this.variantCount = Math.max(1, Math.min(count, 5)); // 1~5 个
        return this;
    }

    /**
     * ⭐ 生成查询变体。
     *
     * @param originalQuery 原始用户问题
     * @return 查询变体列表（第一个是原始 query）
     */
    public List<String> generate(String originalQuery) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return List.of("");
        }

        if (llmCall == null) {
            log.warn("[MultiQuery] LLM 调用器未配置，返回原始 query");
            return List.of(originalQuery);
        }

        try {
            String prompt = String.format("""
                    你是一个搜索查询扩展助手。给定一个用户问题，请从%d个不同角度生成%d个搜索查询变体。
                    每个变体应该用不同的表达方式、关键词或角度重新表述原问题。
                    
                    要求：
                    1. 变体之间要有差异（表达方式、关键词、侧重点不同）
                    2. 保持原问题的核心语义不变
                    3. 不要添加原问题中不存在的限定条件
                    4. 每行一个变体，不要序号和额外文字
                    5. 直接输出变体列表
                    
                    用户问题：%s
                    """, variantCount, variantCount, originalQuery);

            String response = llmCall.apply(prompt);

            if (response == null || response.isBlank()) {
                return List.of(originalQuery);
            }

            List<String> variants = new ArrayList<>();
            // 第一个永远是原始 query
            variants.add(originalQuery);

            for (String line : response.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // 清理可能的序号前缀："1. xxx"、"1、xxx"、"- xxx"
                String cleaned = line.replaceAll("^\\d+[.、]\\s*", "")
                        .replaceAll("^-\\s*", "")
                        .replaceAll("^[•●]\\s*", "")
                        .trim();
                if (!cleaned.isEmpty() && !cleaned.equalsIgnoreCase(originalQuery)) {
                    variants.add(cleaned);
                }
            }

            // 去重 + 限制
            variants = variants.stream().distinct().limit(5).collect(Collectors.toList());

            log.debug("[MultiQuery] 原始='{}' → 变体={}", originalQuery, variants);
            return variants;

        } catch (Exception e) {
            log.warn("[MultiQuery] 生成失败: {}", e.getMessage());
            return List.of(originalQuery);
        }
    }

    /**
     * 计算 RRF 融合分数。
     *
     * @param rankings 各路召回的排名列表，每个元素是一个排名列表 [docId1, docId2, ...]
     * @param k        RRF 常数（通常 60）
     * @return docId → RRF 分数
     */
    public static java.util.Map<String, Double> computeRRF(
            List<List<String>> rankings, int k) {
        java.util.Map<String, Double> scores = new java.util.LinkedHashMap<>();
        for (List<String> ranking : rankings) {
            for (int i = 0; i < ranking.size(); i++) {
                String docId = ranking.get(i);
                scores.merge(docId, 1.0 / (k + i + 1), Double::sum);
            }
        }
        return scores;
    }
}
