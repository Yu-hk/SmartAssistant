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
 * <p>使用方式：</p>
 * <pre>
 * String prompt = PromptBuilder.build()
 *     .withServicePrompt(servicePrompt)
 *     .withDynamicContext(userProfile)
 *     .assemble();
 * </pre>
 */
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    /** 基础提示词文件路径（classpath） */
    private static final String BASE_PROMPT_PATH = "prompts/base-prompt.txt";

    /** 缓存基础提示词（只加载一次） */
    private static String basePrompt;

    private String servicePrompt;
    private String dynamicContext;

    private PromptBuilder() {}

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
     * 设置动态上下文（可选）。
     */
    public PromptBuilder withDynamicContext(String dynamicContext) {
        this.dynamicContext = dynamicContext;
        return this;
    }

    /**
     * 组装最终提示词。
     *
     * @return 按 base → service → dynamic 顺序拼接的完整提示词
     */
    public String assemble() {
        StringBuilder sb = new StringBuilder();

        // Layer 1: 基础规则
        sb.append(getBasePrompt()).append("\n\n");

        // Layer 2: 业务特有指令
        if (servicePrompt != null && !servicePrompt.isBlank()) {
            sb.append(servicePrompt);
        }

        // Layer 3: 动态上下文
        if (dynamicContext != null && !dynamicContext.isBlank()) {
            sb.append("\n\n").append(dynamicContext);
        }

        return sb.toString();
    }

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
