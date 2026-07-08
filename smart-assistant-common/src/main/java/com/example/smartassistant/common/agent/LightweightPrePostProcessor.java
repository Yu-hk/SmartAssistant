/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 轻量模型前后置处理器 — 对标文章⑧「小模型两头包，大模型中间干」。
 *
 * <p>在 Agent 执行链路的两端插入小模型处理节点：</p>
 * <ul>
 *   <li><b>前置处理</b>：大模型调用前，用小模型做意图验证、查询规范化</li>
 *   <li><b>后置处理</b>：大模型/Agent 输出后，用小模型做质量校验、结果摘要</li>
 * </ul>
 *
 * <p>通过"小→大→小"三段流水线，在保证效果的前提下降低大模型 Token 消耗，
 * 提升整体吞吐。</p>
 */
public class LightweightPrePostProcessor {

    private static final Logger log = LoggerFactory.getLogger(LightweightPrePostProcessor.class);

    private final ChatClient lightClient;

    public LightweightPrePostProcessor(ChatClient lightClient) {
        this.lightClient = lightClient;
    }

    /**
     * 前置处理：用小模型验证并规范化用户查询，返回优化后的查询。
     *
     * @param rawQuery      原始用户输入
     * @param candidateIntent 候选意图（来自 IntentFusionService）
     * @return 规范化后的查询（如果小模型认为意图不匹配则加前缀标记）
     */
    public PreProcessResult preProcess(String rawQuery, String candidateIntent) {
        if (rawQuery == null || rawQuery.isBlank() || lightClient == null) {
            return new PreProcessResult(rawQuery, candidateIntent, true, "");
        }

        try {
            String prompt = """
                    你是一个智能助手的前置处理器。请判断用户的输入意图是否与给出的意图标签匹配。
                    只回答 JSON，不要解释。

                    用户输入：%s
                    候选意图：%s

                    回答格式：{"match": true/false, "suggestion": "如果不匹配，给出修正建议"}
                    """.formatted(rawQuery, candidateIntent);

            String response = lightClient.prompt().user(prompt).call().content();
            if (response == null || response.isBlank()) {
                return new PreProcessResult(rawQuery, candidateIntent, true, "");
            }

            boolean match = response.contains("\"match\": true") || response.contains("\"match\":true");
            String suggestion = "";
            if (response.contains("\"suggestion\"")) {
                int start = response.indexOf("\"suggestion\"") + 12;
                int colon = response.indexOf(':', start);
                int quote1 = response.indexOf('"', colon + 1);
                int quote2 = quote1 > 0 ? response.indexOf('"', quote1 + 1) : -1;
                if (quote1 > 0 && quote2 > quote1) {
                    suggestion = response.substring(quote1 + 1, quote2);
                }
            }

            if (!match) {
                log.info("[PrePostProcessor] ⚠️ 前置校验: intent可能不匹配, raw={}, intent={}, suggestion={}",
                        truncate(rawQuery, 30), candidateIntent, suggestion);
            }

            return new PreProcessResult(rawQuery, match ? candidateIntent : candidateIntent + "_UNSURE",
                    match, suggestion);
        } catch (Exception e) {
            log.debug("[PrePostProcessor] 前置处理异常(不影响主流程): {}", e.getMessage());
            return new PreProcessResult(rawQuery, candidateIntent, true, "");
        }
    }

    /**
     * 后置处理：用小模型校验 Agent 回复质量，返回评估和改进建议。
     *
     * @param question     用户问题
     * @param agentReply   Agent 回复
     * @return 质量评估结果
     */
    public PostProcessResult postProcess(String question, String agentReply) {
        if (question == null || agentReply == null || lightClient == null) {
            return new PostProcessResult(true, 1.0, "");
        }

        try {
            String prompt = """
                    你是一个智能助手的质量校验器。评估以下回复是否完整回答了用户问题。
                    只回答 JSON，不要解释。

                    用户问题：%s

                    Agent 回复：%s

                    回答格式：{"pass": true/false, "score": 0.0~1.0, "suggestion": "如果不通过给出改进建议"}
                    """.formatted(truncate(question, 200), truncate(agentReply, 500));

            String response = lightClient.prompt().user(prompt).call().content();
            if (response == null || response.isBlank()) {
                return new PostProcessResult(true, 1.0, "");
            }

            boolean pass = response.contains("\"pass\": true") || response.contains("\"pass\":true");
            double score = 1.0;
            String suggestion = "";

            // 提取 score
            int scoreIdx = response.indexOf("\"score\"");
            if (scoreIdx > 0) {
                int colon = response.indexOf(':', scoreIdx);
                int end = response.indexOf(',', colon);
                if (end < 0) end = response.indexOf('}', colon);
                if (end > colon) {
                    try { score = Double.parseDouble(response.substring(colon + 1, end).trim()); } catch (Exception ignored) {}
                }
            }

            // 提取 suggestion
            int sugIdx = response.indexOf("\"suggestion\"");
            if (sugIdx > 0) {
                int colon = response.indexOf(':', sugIdx);
                int q1 = response.indexOf('"', colon + 1);
                int q2 = q1 > 0 ? response.indexOf('"', q1 + 1) : -1;
                if (q1 > 0 && q2 > q1) suggestion = response.substring(q1 + 1, q2);
            }

            if (!pass) {
                log.info("[PrePostProcessor] ⚠️ 后置校验: 回复质量不足, score={}, suggestion={}", score, suggestion);
            }

            return new PostProcessResult(pass, score, suggestion);
        } catch (Exception e) {
            log.debug("[PrePostProcessor] 后置处理异常(不影响主流程): {}", e.getMessage());
            return new PostProcessResult(true, 1.0, "");
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /** 前置处理结果。 */
    public record PreProcessResult(
            String normalizedQuery,
            String refinedIntent,
            boolean match,
            String suggestion) {}

    /** 后置处理结果。 */
    public record PostProcessResult(
            boolean pass,
            double score,
            String suggestion) {}
}
