/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 分层提示词组装器。
 * <p>
 * 将 system prompt 组织为三层结构：
 * <ol>
 *   <li><b>base layer</b> — 通用行为规则（common 模块内置）</li>
 *   <li><b>service layer</b> — 业务特有指令（各服务自己的 prompt）</li>
 *   <li><b>dynamic layer</b> — 运行期注入的上下文（可选）</li>
 * </ol>
 * </p>
 *
 * <p>支持模板变量替换（{@code ${var}} 格式）和条件注入。</p>
 *
 * <p>使用方式：</p>
 * <pre>
 * String prompt = PromptBuilder.build()
 *     .withServicePrompt(servicePrompt)
 *     .withDynamicContext(userProfile)
 *     .withVar("agentName", "商品咨询助手")
 *     .withSection("工具说明", "可用工具：...")
 *     .assemble();
 * </pre>
 */
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    /** 基础提示词文件路径（classpath） */
    private static final String BASE_PROMPT_PATH = "prompts/base-prompt.txt";

    /** project-context 最大字符数（≈2000 tokens） */
    private static final int MAX_PROJECT_CONTEXT_CHARS = 8000;

    /** project-context 截断标记 */
    private static final String PROJECT_CONTEXT_TRUNCATED_SUFFIX = "...[project-context 已截断]";

    /** 缓存基础提示词（只加载一次） */
    private static String basePrompt;

    private String servicePrompt;
    private String dynamicContext;

    /** AI 项目指令手册（project-context 层） */
    private String projectContext;

    /** ⭐ 模板变量映射：${var} → value */
    private final Map<String, String> variables = new LinkedHashMap<>();

    /** ⭐ 额外章节：章节名 → 内容（可选，在 service 层后追加） */
    private final List<Map.Entry<String, String>> sections = new ArrayList<>();

    private PromptBuilder() {
        // 注入默认变量
        withVar("currentDate", LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        withVar("currentTime", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
    }

    /**
     * 创建 PromptBuilder 实例。
     */
    public static PromptBuilder build() {
        return new PromptBuilder();
    }

    /**
     * 设置业务层提示词。
     */
    public PromptBuilder withServicePrompt(String servicePrompt) {
        this.servicePrompt = servicePrompt;
        return this;
    }

    /**
     * 注入 AI 项目指令手册（project-context 层）。
     * <p>注入顺序：base → projectContext → service → sections → dynamic。
     * 超过 8000 字符时尾部截断 + 追加截断标记。
     * projectContext 为 null/空时跳过。</p>
     *
     * @param projectContext 项目上下文文本
     * @return this
     */
    public PromptBuilder withProjectContext(String projectContext) {
        this.projectContext = projectContext;
        return this;
    }

    /**
     * 设置动态上下文（可选）。
     */
    public PromptBuilder withDynamicContext(String dynamicContext) {
        this.dynamicContext = dynamicContext;
        return this;
    }

    /**
     * ⭐ 注入模板变量。
     * <p>在最终提示词中替换 {@code ${varName}} 占位符。
     * 内置变量：{@code ${currentDate}}, {@code ${currentTime}}。
     *
     * @param varName 变量名（不含 ${} 前缀）
     * @param value   变量值
     * @return this
     */
    public PromptBuilder withVar(String varName, String value) {
        if (varName != null && value != null) {
            variables.put(varName, value);
        }
        return this;
    }

    /**
     * ⭐ 批量注入模板变量。
     */
    public PromptBuilder withVars(Map<String, String> vars) {
        if (vars != null) {
            variables.putAll(vars);
        }
        return this;
    }

    /**
     * ⭐ 添加额外章节。
     * <p>在 service 层之后、dynamic 层之前插入。
     *
     * @param title   章节标题
     * @param content 章节内容
     * @return this
     */
    public PromptBuilder withSection(String title, String content) {
        if (title != null && content != null && !content.isBlank()) {
            sections.add(Map.entry(title, content));
        }
        return this;
    }

    /**
     * ⭐ 条件注入——仅在条件满足时添加章节。
     *
     * @param title    章节标题
     * @param content  章节内容
     * @param condition 条件
     * @return this
     */
    public PromptBuilder withSectionIf(String title, String content, boolean condition) {
        if (condition) {
            withSection(title, content);
        }
        return this;
    }

    /**
     * 组装最终提示词。
     *
     * @return 按 base → projectContext → service → sections → dynamic 顺序拼接的完整提示词
     */
    public String assemble() {
        StringBuilder sb = new StringBuilder();

        // Layer 1: 基础规则
        sb.append(resolveVars(getBasePrompt())).append("\n\n");

        // Layer 1.5: AI 项目指令手册（project-context 层）
        if (projectContext != null && !projectContext.isBlank()) {
            String truncated = projectContext;
            if (truncated.length() > MAX_PROJECT_CONTEXT_CHARS) {
                truncated = truncated.substring(0, MAX_PROJECT_CONTEXT_CHARS)
                        + PROJECT_CONTEXT_TRUNCATED_SUFFIX;
            }
            sb.append(resolveVars(truncated)).append("\n\n");
        }

        // Layer 2: 业务特有指令
        if (servicePrompt != null && !servicePrompt.isBlank()) {
            sb.append(resolveVars(servicePrompt));
        }

        // Layer 2.5: 额外章节
        for (Map.Entry<String, String> section : sections) {
            sb.append("\n\n【").append(section.getKey()).append("】\n");
            sb.append(resolveVars(section.getValue()));
        }

        // Layer 3: 动态上下文
        if (dynamicContext != null && !dynamicContext.isBlank()) {
            sb.append("\n\n").append(resolveVars(dynamicContext));
        }

        return sb.toString().trim();
    }

    /**
     * ⭐ 解析模板变量。
     * <p>替换文本中所有 {@code ${varName}} 占位符。
     */
    private String resolveVars(String text) {
        if (text == null || variables.isEmpty()) return text;
        String result = text;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    // ==================== 原 getBasePrompt 保持不变 ====================

    /**
     * 加载基础提示词（带缓存）。
     */
    private static String getBasePrompt() {
        if (basePrompt != null) return basePrompt;

        try (InputStream is = PromptBuilder.class.getClassLoader()
                .getResourceAsStream(BASE_PROMPT_PATH)) {
            if (is == null) {
                log.warn("[PromptBuilder] 未找到基础提示词文件: {}", BASE_PROMPT_PATH);
                basePrompt = "";
                return basePrompt;
            }
            basePrompt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            log.info("[PromptBuilder] 加载基础提示词 ({} 字符)", basePrompt.length());
        } catch (IOException e) {
            log.warn("[PromptBuilder] 加载基础提示词失败: {}", e.getMessage());
            basePrompt = "";
        }
        return basePrompt;
    }
}
