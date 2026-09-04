/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 统一 Prompt 管理器。
 *
 * <p>集中管理当前生产链路实际使用的 System Prompt，并缓存 classpath 读取结果。
 *
 * <p>目录结构：</p>
 * <pre>{@code
 * prompts/
 * ├── base-prompt.txt                   # 通用基础层
 * ├── general/general-system-prompt.txt
 * ├── product/product-system-prompt.txt
 * ├── order/order-system-prompt.txt
 * ├── router/
 * │   ├── rag-summary.txt               # RAG 对话摘要
 * │   └── rag-entity.txt                # RAG 实体提取
 * ├── consumer/                         # 混合查询、用户画像
 * └── analysis/                         # 数据分析、推荐核实
 * }</pre>
 */
@Service
public class PromptManager {

    private static final Logger log = LoggerFactory.getLogger(PromptManager.class);

    /** 已加载的 Prompt 缓存（key=路径） */
    private final Map<String, String> promptCache = new ConcurrentHashMap<>();

    private final PathMatchingResourcePatternResolver resourceLoader =
            new PathMatchingResourcePatternResolver();

    // ═══════════════════════════════════════════════════════════
    // 核心 API
    // ═══════════════════════════════════════════════════════════

    /**
     * 加载指定路径的 Prompt 文件。
     *
     * @param classpathResource classpath 路径，如 "prompts/router/inline-fallback.txt"
     * @return Prompt 内容
     */
    public String load(String classpathResource) {
        return promptCache.computeIfAbsent(classpathResource, this::readResource);
    }

    // ═══════════════════════════════════════════════════════════
    // 便捷加载方法
    // ═══════════════════════════════════════════════════════════

    /** 加载 RAG 对话摘要 Prompt */
    public String ragSummary() {
        return load("prompts/router/rag-summary.txt");
    }

    /** 加载 RAG 实体提取 Prompt */
    public String ragEntityExtraction() {
        return load("prompts/router/rag-entity.txt");
    }

    /** 加载订单意图分类 Prompt */
    public String orderIntentClassifier() {
        return load("prompts/order/intent-classifier.txt");
    }

    /** 加载 Text-to-SQL Prompt */
    public String textToSql() {
        return load("prompts/order/text-to-sql.txt");
    }

    /** 加载数据分析专家 Prompt 模板。 */
    public String dataAnalysisExpert() {
        return load("prompts/analysis/data-analysis-expert.txt");
    }

    /**
     * 渲染数据分析专家 Prompt。
     *
     * <p>调用方必须把真实工具结果或明确的数据获取约束传入 {@code context}；
     * 数据为空时模板会要求模型明确披露限制，而不是补造结论。</p>
     */
    public String renderDataAnalysisExpert(String query, String context) {
        String safeQuery = query == null || query.isBlank() ? "未提供具体问题" : query.trim();
        String safeContext = context == null || context.isBlank()
                ? "当前尚未提供可验证数据。必须先调用数据工具；若仍无数据，明确说明数据限制。"
                : context.trim();
        return dataAnalysisExpert()
                .replace("{{query}}", safeQuery)
                .replace("{{context}}", safeContext);
    }

    /** 渲染 Pro 单次完成事实核实与最终推荐的结构化 Prompt。 */
    public String renderProductAuditAndRecommendation(String query, String context) {
        return load("prompts/analysis/product-audit-recommendation.txt")
                .replace("{{query}}", safePromptValue(query, "未提供具体推荐目标"))
                .replace("{{context}}", safePromptValue(context, "未提供候选商品或分析结果"));
    }

    private static String safePromptValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 加载混合查询 Prompt */
    public String hybridQuery() {
        return load("prompts/consumer/hybrid-query.txt");
    }

    /** 加载用户洞察与消费心理分析 Prompt。 */
    public String userProfileAnalysis() {
        return load("prompts/consumer/user-profile-analysis.txt");
    }

    /** 渲染用户画像分析 Prompt，输入只作为待分析数据。 */
    public String renderUserProfileAnalysis(String conversationHistory) {
        return renderUserProfileAnalysis("当前没有已保存画像。", conversationHistory);
    }

    /** 渲染带当前画像快照的增量用户画像分析 Prompt。 */
    public String renderUserProfileAnalysis(String currentProfile, String conversationHistory) {
        return userProfileAnalysis()
                .replace("{{current_profile}}",
                        safePromptValue(currentProfile, "当前没有已保存画像。"))
                .replace("{{conversation_history}}",
                        safePromptValue(conversationHistory, "当前没有可分析的对话原文。"));
    }

    // ═══════════════════════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════════════════════

    private String readResource(String classpathResource) {
        try {
            Resource resource = resourceLoader.getResource("classpath:" + classpathResource);
            if (!resource.exists()) {
                log.warn("[PromptManager] Prompt 文件不存在: {}", classpathResource);
                return "";
            }
            try (InputStream is = resource.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String content = reader.lines().collect(Collectors.joining("\n"));
                log.info("[PromptManager] 加载 Prompt: {} ({} bytes)", classpathResource, content.length());
                return content;
            }
        } catch (Exception e) {
            log.warn("[PromptManager] 加载 Prompt 失败: {}, error={}", classpathResource, e.getMessage());
            return "";
        }
    }

}
