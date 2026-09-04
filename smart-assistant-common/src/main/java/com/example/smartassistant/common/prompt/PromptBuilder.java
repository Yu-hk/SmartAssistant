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
 * Agent 系统提示词组装器。
 *
 * <p>生产链路只需要稳定的两层结构：Common 基础规则和模块业务规则。
 * 运行期画像、工具能力与请求上下文分别由 Advisor、Skill 和调用参数注入，
 * 不在这里重复维护另一套模板系统。</p>
 */
public final class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);
    private static final String BASE_PROMPT_PATH = "prompts/base-prompt.txt";

    private static volatile String basePrompt;

    private String servicePrompt;

    private PromptBuilder() {
    }

    public static PromptBuilder build() {
        return new PromptBuilder();
    }

    public PromptBuilder withServicePrompt(String servicePrompt) {
        this.servicePrompt = servicePrompt;
        return this;
    }

    /** 按“公共基础规则 → 业务规则”的顺序组装。 */
    public String assemble() {
        String base = getBasePrompt();
        String service = servicePrompt == null ? "" : servicePrompt.trim();
        if (base.isBlank()) {
            return service;
        }
        if (service.isBlank()) {
            return base.trim();
        }
        return base.trim() + "\n\n" + service;
    }

    private static String getBasePrompt() {
        String cached = basePrompt;
        if (cached != null) {
            return cached;
        }
        synchronized (PromptBuilder.class) {
            if (basePrompt != null) {
                return basePrompt;
            }
            try (InputStream input = PromptBuilder.class.getClassLoader()
                    .getResourceAsStream(BASE_PROMPT_PATH)) {
                if (input == null) {
                    log.warn("[PromptBuilder] 未找到基础提示词文件: {}", BASE_PROMPT_PATH);
                    basePrompt = "";
                } else {
                    basePrompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException error) {
                log.warn("[PromptBuilder] 加载基础提示词失败: {}", error.getMessage());
                basePrompt = "";
            }
            return basePrompt;
        }
    }
}
