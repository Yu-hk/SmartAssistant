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
 * <p>集中管理所有 System Prompt，支持按角色/场景加载、版本管理（A/B 灰度）。
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
 * ├── consumer/
 * │   └── mcp-agent.txt                 # MCP Agent
 * └── common/
 *     ├── summary-compression.txt       # 对话摘要压缩
 *     ├── entity-extraction.txt         # 实体提取
 *     └── query-rewrite.txt             # 查询改写
 * }</pre>
 *
 * <p>版本约定：{@code filename-v2.txt} 为 v2 版本，默认加载不带版本号的版本。</p>
 */
@Service
public class PromptManager {

    private static final Logger log = LoggerFactory.getLogger(PromptManager.class);

    /** 已加载的 Prompt 缓存（key=路径） */
    private final Map<String, String> promptCache = new ConcurrentHashMap<>();

    /** 已加载的版本映射（key=文件名, value=版本号） */
    private final Map<String, String> versionMap = new ConcurrentHashMap<>();

    /** AI 项目指令手册路径（classpath） */
    private static final String PROJECT_CONTEXT_PATH = "ai-project-context.md";

    /** AI 项目指令手册缓存（null = 未加载或文件不存在） */
    private volatile String projectContext;

    /** AI 项目指令手册是否已尝试加载 */
    private volatile boolean projectContextLoaded = false;

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

    /**
     * 加载指定路径的 Prompt，并应用版本号。
     *
     * @param basePath  基础路径，如 "prompts/router/inline-fallback"
     * @param version   版本号，如 "v2"。null 或空串时加载无版本号版本
     * @return Prompt 内容
     */
    public String loadWithVersion(String basePath, String version) {
        String resourcePath = (version != null && !version.isBlank())
                ? basePath + "-" + version + ".txt"
                : basePath + ".txt";
        return load(resourcePath);
    }

    /**
     * 重置缓存（用于动态刷新）。
     */
    public void reloadAll() {
        promptCache.clear();
        versionMap.clear();
        projectContext = null;
        projectContextLoaded = false;
        log.info("[PromptManager] 缓存已清空，下次加载将重新读取文件");
    }

    /**
     * 检查指定版本是否存在。
     */
    public boolean hasVersion(String basePath, String version) {
        String resourcePath = basePath + "-" + version + ".txt";
        return resourceLoader.getResource("classpath:" + resourcePath).exists();
    }

    /**
     * 根据灰度比例随机选择版本。
     *
     * @param basePath   基础路径
     * @param v2Ratio    v2 版本的流量比例（0.0~1.0）
     * @param userHash   用户哈希值（同一用户始终命中同一版本）
     * @return Prompt 内容（含版本信息）
     */
    public VersionedPrompt loadWithCanary(String basePath, double v2Ratio, int userHash) {
        String version = (userHash % 1000) < (v2Ratio * 1000) ? "v2" : null;
        String content = loadWithVersion(basePath, version);
        return new VersionedPrompt(content, version != null ? version : "v1");
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

    /** 渲染 Pro 推荐模型使用的分析事实核实 Prompt。 */
    public String renderProductAnalysisAudit(String query, String context) {
        return load("prompts/analysis/product-analysis-audit.txt")
                .replace("{{query}}", safePromptValue(query, "未提供具体推荐目标"))
                .replace("{{context}}", safePromptValue(context, "未提供候选商品或分析结果"));
    }

    /** 渲染 Pro 推荐模型使用的最终推荐 Prompt。 */
    public String renderVerifiedProductRecommendation(String query, String context) {
        return load("prompts/analysis/verified-product-recommendation.txt")
                .replace("{{query}}", safePromptValue(query, "未提供具体推荐目标"))
                .replace("{{context}}", safePromptValue(context, "未提供已核实分析结果"));
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

    /** 加载 MCP Agent Prompt */
    public String mcpAgent() {
        return load("prompts/consumer/mcp-agent.txt");
    }

    /** 加载混合查询 Prompt */
    public String hybridQuery() {
        return load("prompts/consumer/hybrid-query.txt");
    }

    /** 加载对话摘要压缩 Prompt */
    public String summaryCompression() {
        return load("prompts/common/summary-compression.txt");
    }

    /** 加载前置处理 Prompt */
    public String preprocessor() {
        return load("prompts/common/preprocessor.txt");
    }

    /** 加载后置处理 Prompt */
    public String postprocessor() {
        return load("prompts/common/postprocessor.txt");
    }

    /** 加载实体提取 Prompt */
    public String entityExtraction() {
        return load("prompts/common/entity-extraction.txt");
    }

    /** 加载查询改写 Prompt */
    public String queryRewrite() {
        return load("prompts/common/query-rewrite.txt");
    }

    /**
     * 加载 AI 项目指令手册（ai-project-context.md）。
     * <p>从 classpath 加载，带缓存。文件不存在时 WARN 日志 + 返回 null。</p>
     *
     * @return 项目上下文文本，文件不存在时返回 null
     */
    public String loadProjectContext() {
        if (projectContextLoaded) {
            return projectContext;
        }
        synchronized (this) {
            if (projectContextLoaded) {
                return projectContext;
            }
            Resource resource = resourceLoader.getResource("classpath:" + PROJECT_CONTEXT_PATH);
            if (!resource.exists()) {
                log.warn("[PromptManager] AI 项目指令手册不存在: {}", PROJECT_CONTEXT_PATH);
                projectContext = null;
                projectContextLoaded = true;
                return null;
            }
            projectContext = readResource(PROJECT_CONTEXT_PATH);
            projectContextLoaded = true;
            log.info("[PromptManager] 加载 AI 项目指令手册 ({} 字符)",
                    projectContext != null ? projectContext.length() : 0);
            return projectContext;
        }
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

    // ═══════════════════════════════════════════════════════════
    // 带版本信息的 Prompt
    // ═══════════════════════════════════════════════════════════

    public record VersionedPrompt(String content, String version) {}
}
