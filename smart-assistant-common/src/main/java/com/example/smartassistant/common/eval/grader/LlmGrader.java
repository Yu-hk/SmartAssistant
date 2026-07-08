/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval.grader;

import com.example.smartassistant.common.eval.AgentEvaluationResult;
import com.example.smartassistant.common.eval.EvaluationReportService.AgentTestSpec;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-Judge 打分器 — 瀑布流第二环，对规则无法判定的语义质量（语气/策略/合理性）做弹性评分。
 *
 * <p>严格遵循文章《Agent 评测体系》§6 的 3 大偏差防护：</p>
 * <ul>
 *   <li><b>长度偏差</b>：过长（&gt;2000 字）且高分 → 封顶 0.85；过短（&lt;10 字）且高分 → 封顶 0.6；</li>
 *   <li><b>位置偏差</b>：评测 prompt 不提供「参考答案位置」，避免模型盲从首条；</li>
 *   <li><b>自我偏好偏差</b>：prompt 强制「严格、挑刺」，且无 rationale 时置信度强制降级。</li>
 * </ul>
 *
 * <p>无 rationale 或解析失败 → confidence 降至 0.3 并标记需人工复核，避免静默误判。</p>
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public class LlmGrader implements Grader {

    private static final Logger log = LoggerFactory.getLogger(LlmGrader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_PATTERN = Pattern.compile("[\\{].*?[\\}]", Pattern.DOTALL);

    private final ChatModel chatModel;
    private final double humanReviewConfidenceThreshold;

    public LlmGrader(ChatModel chatModel) {
        this(chatModel, 0.6);
    }

    public LlmGrader(ChatModel chatModel, double humanReviewConfidenceThreshold) {
        this.chatModel = chatModel;
        this.humanReviewConfidenceThreshold = humanReviewConfidenceThreshold;
    }

    @Override
    public GraderResult grade(AgentEvaluationResult result, AgentTestSpec spec) {
        String prompt = buildJudgePrompt(result, spec);
        String raw = safeCall(prompt);
        JudgeOutput out = parse(raw);
        if (out == null) {
            return new GraderResult(result.getCompositeScore(), true,
                    "LLM 解析失败，回退规则分", "LLM", 0.3, true);
        }

        double score = applyBiasGuardrails(out.score, result);
        double confidence = out.confidence;
        if (out.rationale == null || out.rationale.isBlank()) {
            confidence = Math.min(confidence, 0.3); // 无理由 → 不可信
        }
        boolean needHuman = confidence < humanReviewConfidenceThreshold;
        return new GraderResult(score, true, out.rationale, "LLM", confidence, needHuman);
    }

    private String safeCall(String prompt) {
        try {
            ChatResponse resp = chatModel.call(new Prompt(prompt));
            if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
                return null;
            }
            return resp.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[LlmGrader] 模型调用失败: {}", e.getMessage());
            return null;
        }
    }

    private JudgeOutput parse(String raw) {
        if (raw == null) return null;
        try {
            Matcher m = JSON_PATTERN.matcher(raw);
            String json = m.find() ? m.group() : raw;
            JsonNode node = MAPPER.readTree(json);
            JudgeOutput o = new JudgeOutput();
            o.score = node.has("score") ? node.get("score").asDouble() : 0.0;
            o.rationale = node.has("rationale") ? node.get("rationale").asText() : "";
            o.confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.5;
            return o;
        } catch (Exception e) {
            log.warn("[LlmGrader] JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 3 大偏差防护：长度偏差封顶。 */
    private double applyBiasGuardrails(double score, AgentEvaluationResult result) {
        String resp = result.getActualResponse();
        if (resp == null) return score;
        int len = resp.length();
        if (len > 2000 && score > 0.85) return 0.85;
        if (len < 10 && score > 0.6) return 0.6;
        return score;
    }

    private String buildJudgePrompt(AgentEvaluationResult result, AgentTestSpec spec) {
        return """
                你是一名严格的 Agent 回复质量评审专家。请基于【预期】与【实际】判断回复质量，挑刺优先。
                只输出 JSON，不要解释。格式：{"score":0.0~1.0,"rationale":"简要理由","confidence":0.0~1.0}

                【预期意图】%s
                【预期工具】%s
                【预期关键词】%s
                【用户输入】%s
                【实际意图】%s
                【实际工具】%s
                【实际回复】%s
                """.formatted(
                spec.expectedIntent, spec.expectedTools, spec.expectedKeywords, spec.input,
                result.getActualIntent(), result.getActualToolsCalled(), result.getActualResponse());
    }

    /** LLM 返回的打分结构。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class JudgeOutput {
        double score;
        String rationale;
        double confidence;
    }
}
