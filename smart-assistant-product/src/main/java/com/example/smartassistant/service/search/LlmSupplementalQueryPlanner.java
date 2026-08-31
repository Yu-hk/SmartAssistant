/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/** Provider-neutral LLM planner used only when the first retrieval lacks evidence. */
public class LlmSupplementalQueryPlanner implements SupplementalQueryPlanner {

    private static final Logger log = LoggerFactory.getLogger(LlmSupplementalQueryPlanner.class);
    private static final int MAX_QUERY_CHARS = 240;

    private final ChatClient chatClient;

    public LlmSupplementalQueryPlanner(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String plan(String originalQuery, String previousQuery, String evidenceSummary, int nextAttempt) {
        String prompt = """
                你是只读知识检索规划器。首轮检索证据不足，请生成一个补充检索查询。
                规则：
                1. 保持用户原始意图，不新增品牌、型号、价格、库存等事实；
                2. 针对缺失信息换一个检索角度，不能重复上一轮查询；
                3. 只输出一条查询，不输出分析、标签、引号或思考过程；
                4. 禁止调用或描述任何业务写操作。

                用户原始问题：%s
                上一轮查询：%s
                已有证据摘要：%s
                补充检索查询：
                """.formatted(safe(originalQuery), safe(previousQuery), safe(evidenceSummary));
        try {
            String planned = chatClient.prompt().user(prompt).call().content();
            return sanitize(planned, previousQuery);
        } catch (Exception e) {
            log.warn("[NativeRAG] 补检规划失败，停止补检: {}", e.getMessage());
            return "";
        }
    }

    private static String sanitize(String value, String previousQuery) {
        if (value == null) return "";
        String cleaned = value.replaceAll("(?is)<think(?:ing)?>.*?</think(?:ing)?>", "")
                .replace("```", "")
                .replaceAll("^[\\s\"'“”]+|[\\s\"'“”]+$", "")
                .strip();
        int newline = cleaned.indexOf('\n');
        if (newline >= 0) cleaned = cleaned.substring(0, newline).strip();
        if (cleaned.length() > MAX_QUERY_CHARS) cleaned = cleaned.substring(0, MAX_QUERY_CHARS);
        return cleaned.equalsIgnoreCase(safe(previousQuery)) ? "" : cleaned;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
