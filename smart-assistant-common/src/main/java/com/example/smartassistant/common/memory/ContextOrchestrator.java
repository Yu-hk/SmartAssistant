/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文协调器 — 统一调度四层记忆预算。
 *
 * <p>参考文章⑧的分层记忆架构：</p>
 * <ul>
 *   <li><b>短期区</b>：最近 3-5 轮原文（由 SmartReActAgent 的 keepRounds 管理）</li>
 *   <li><b>中期区</b>：递归滚动摘要（由 SmartReActAgent.summaryChain 管理）</li>
 *   <li><b>长期区</b>：用户偏好记忆（由 AgentMemoryService 管理）</li>
 *   <li><b>状态锚点</b>：结构化用户画像（由 getStateAnchor() 管理）</li>
 * </ul>
 *
 * <p>协调器在每个新问题到来时，从四层分别获取最相关的内容，
 * 在 Token 预算约束下拼接成最终 prompt。</p>
 */
@Component
public class ContextOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ContextOrchestrator.class);

    /** 默认 Token 预算 (8K) */
    static final int DEFAULT_BUDGET = 8000;
    /** 各层预算分配（文章⑧推荐值） */
    static final int BUDGET_STATE = 500;
    static final int BUDGET_SHORT_TERM = 2500;
    static final int BUDGET_MEDIUM = 1500;
    static final int BUDGET_LONG = 2000;
    static final int BUDGET_QUESTION = 1500;

    private final AgentMemoryService memoryService;

    public ContextOrchestrator(AgentMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * 构建分层 prompt。
     *
     * @param question 用户当前问题
     * @param userId   用户 ID（可为 null）
     * @param agent    Agent 名称（如 "order"）
     * @param extras   额外上下文片段（如 RAG 结果、摘要链等，可为空）
     * @return 分层组装后的 prompt
     */
    public String buildPrompt(String question, String userId, String agent, List<String> extras) {
        List<String> layers = new ArrayList<>();

        // 1. 状态锚点 (500T)
        layers.add(memoryService.getStateAnchor(userId));

        // 2. 长期区 — 用户偏好记忆 (2000T)
        String userMemory = (userId != null && agent != null)
                ? memoryService.getAllFormatted(agent, userId, question)
                : "";
        if (!userMemory.isBlank()) {
            layers.add(userMemory);
        }

        // 3. 中长期 — 额外上下文（RAG/知识库等）(1500T)
        if (extras != null) {
            for (String extra : extras) {
                if (extra != null && !extra.isBlank()) {
                    layers.add(extra);
                }
            }
        }

        // 4. 当前问题 (1500T)
        layers.add("[用户问题]\n" + question);

        String result = String.join("\n\n", layers);
        log.debug("[ContextOrchestrator] 分层 prompt ({} 层)", layers.size());
        return result;
    }
}
