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
     * ⭐ Step-back 策略：先问背景，再答细节。
     * <p>
     * 当用户问题太具体时，先退一步生成更通用的背景问题，
     * 背景问题在知识库中更容易命中，再结合背景回答具体问题。
     * </p>
     * <p>
     * 例如 "为什么 attention 要除以 sqrt(d_k)" →
     * "缩放点积注意力的数学原理"
     * </p>
     *
     * @param specificQuery 用户具体问题
     * @return 背景问题（通用化后的版本）
     */
    public String generateStepBackQuery(String specificQuery) {
        if (specificQuery == null || specificQuery.isBlank()) return "";
        if (llmCall == null) return specificQuery;

        try {
            String prompt = String.format("""
                    你是一个搜索策略专家。给定一个非常具体或细节导向的用户问题，
                    请退一步生成一个更通用、更背景性的问题版本，使其更容易在知识库中被检索到。
                    
                    具体规则：
                    - 去掉具体的数值、型号、名称，替换为更通用的表述
                    - 保持问题的核心领域和意图不变
                    - 只输出背景问题，不要解释
                    
                    示例：
                    具体：为什么 iPhone 15 Pro Max 的 A17 Pro 芯片比 A16 快 20%%
                    背景：iPhone 芯片性能升级原理
                    
                    具体：%s
                    背景：
                    """, specificQuery);

            String response = llmCall.apply(prompt);
            if (response == null || response.isBlank()) return specificQuery;
            return response.trim();

        } catch (Exception e) {
            log.warn("[MultiQuery] Step-back 失败: {}", e.getMessage());
            return specificQuery;
        }
    }

    /**
     * ⭐ HyDE 策略：先生成假设答案，再拿答案去检索。
     * <p>
     * 问题是疑问句，文档通常是陈述句。HyDE 先生成一段"可能的答案"，
     * 再用这个假设答案做 Embedding 检索，使 query 的文体更接近文档，
     * 向量匹配更容易命中。
     * </p>
     *
     * @param query 用户问题
     * @return 假设答案（用于嵌入检索的"伪文档"）
     */
    public String generateHydeDoc(String query) {
        if (query == null || query.isBlank()) return "";
        if (llmCall == null) return query;

        try {
            String prompt = String.format("""
                    你是一个文档生成助手。给定一个用户问题，请生成一段看起来像知识库文档的"假设答案"。
                    这段答案要像知识库中的文档一样使用陈述句风格，包含事实性描述。
                    
                    要求：
                    1. 使用陈述句风格（知识库文档通常是陈述句，不是问句）
                    2. 包含具体的关键词和术语（便于向量匹配命中）
                    3. 长度控制在 100-200 字
                    4. 只输出假设答案，不要解释
                    
                    用户问题：%s
                    
                    假设答案（知识库文档风格）：
                    """, query);

            String response = llmCall.apply(prompt);
            if (response == null || response.isBlank()) return query;
            return response.trim();

        } catch (Exception e) {
            log.warn("[MultiQuery] HyDE 失败: {}", e.getMessage());
            return query;
        }
    }

    /** 查询扩展策略枚举 */
    public enum ExpansionStrategy {
        /** 标准 Multi-Query：多个角度变体 */
        MULTI_QUERY,
        /** Step-back：先问背景再答细节 */
        STEP_BACK,
        /** HyDE：先生成假设答案再检索 */
        HYDE
    }

    /**
     * 按指定策略生成扩展查询。
     *
     * @param query    原始用户问题
     * @param strategy 扩展策略
     * @return 扩展后的查询列表
     */
    public List<String> expand(String query, ExpansionStrategy strategy) {
        return switch (strategy) {
            case MULTI_QUERY -> generate(query);
            case STEP_BACK -> {
                String stepBack = generateStepBackQuery(query);
                List<String> result = new ArrayList<>();
                result.add(query);          // 保留原始（具体）query
                if (!stepBack.equals(query)) {
                    result.add(stepBack);   // 追加背景 query
                }
                yield result;
            }
            case HYDE -> {
                String hyde = generateHydeDoc(query);
                List<String> result = new ArrayList<>();
                result.add(query);          // 保留原始 query
                if (!hyde.equals(query)) {
                    result.add(hyde);       // 追加假设答案（用于嵌入检索）
                }
                yield result;
            }
        };
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
