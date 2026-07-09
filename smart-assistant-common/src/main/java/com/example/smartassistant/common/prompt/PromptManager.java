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
 * │   ├── inline-fallback.txt           # 内联兜底 warmSystem
 * │   ├── result-merger.txt             # 结果合并
 * │   └── task-analysis.txt             # 任务分析
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

    /** 加载内联兜底 Prompt */
    public String inlineFallback() {
        return load("prompts/router/inline-fallback.txt");
    }

    /** 加载结果合并 Prompt */
    public String resultMerger() {
        return load("prompts/router/result-merger.txt");
    }

    /** 加载任务分析 Prompt */
    public String taskAnalysis() {
        return load("prompts/router/task-analysis.txt");
    }

    /** 加载质量评估 Prompt */
    public String qualityEvaluation() {
        return load("prompts/router/quality-evaluation.txt");
    }

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
