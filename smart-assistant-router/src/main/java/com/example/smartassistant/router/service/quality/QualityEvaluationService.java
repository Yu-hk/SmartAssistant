/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.quality;

import com.example.smartassistant.router.model.QualityEvaluationResult;
import com.example.smartassistant.router.service.core.ModelRoutingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-Judge 质量评估服务——对 Agent 回复进行语义层面的质量评分。
 * <p>
 * 参照 ThinkingAgent 的任务级质量评估设计，使用 LLM 对回复进行多维度评价：
 * 相关性、完整性、幻觉检测、实用性。
 * </p>
 *
 * <p>与 {@link com.example.smartassistant.router.service.core.ReflectionService 反思器} 的互补关系：</p>
 * <ul>
 *   <li>反思器：纯规则、轻量、无 LLM 调用——做浅层快速过滤（长度/错误标记/关键词）</li>
 *   <li>质检服务：LLM 驱动、重量级——做深层语义评估</li>
 *   <li>反思器先跑，通过后再跑质检（仅在高风险/边界场景触发 LLM 评估）</li>
 * </ul>
 *
 * <p>默认仅在反思器评分处于边界区间（0.60~0.80）时触发 LLM 评估，
 * 以平衡质量保障与延迟开销。</p>
 *
 * @see QualityEvaluationResult
 * @see com.example.smartassistant.router.service.core.ReflectionService
 */
@Service
public class QualityEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(QualityEvaluationService.class);

    private static final String EVALUATION_SYSTEM_PROMPT =
            "你是一个电商 AI 助手的质量评估员。"
            + "评估助手对用户问题的回复质量，从四个维度评分（0.0~1.0）：\n"
            + "1. relevance（相关性）：回复是否直接回答了用户的问题\n"
            + "2. completeness（完整性）：回复是否覆盖了问题的所有方面\n"
            + "3. hallucination（幻觉检测）：回复中是否存在未经验证的声称或捏造信息；有明显虚构内容则扣分\n"
            + "4. helpfulness（实用性）：回复是否清晰、可操作、友好\n\n"
            + "输出格式：仅输出一个合法的 JSON 对象，不要 markdown 代码块标记，不要其他文字。\n"
            + "{\n"
            + "  \"relevance\": 0.0-1.0,\n"
            + "  \"completeness\": 0.0-1.0,\n"
            + "  \"hallucination\": 0.0-1.0,\n"
            + "  \"helpfulness\": 0.0-1.0,\n"
            + "  \"overall\": 0.0-1.0,\n"
            + "  \"reason\": \"简要说明扣分原因，中文\"\n"
            + "}\n\n"
            + "注意：overall 是综合评分，应反映整体质量。reason 应当具体说明主要扣分点。";

    /** 用户提示词模板：{"问题":"...","回复":"..."} */
    private static final String USER_PROMPT_TEMPLATE =
            "{\"问题\":\"%s\",\"回复\":\"%s\"}";

    private final ModelRoutingService modelRoutingService;
    private final ObjectMapper objectMapper;

    @Value("${router.quality-evaluation.enabled:true}")
    private boolean enabled;

    @Value("${router.quality-evaluation.threshold:0.6}")
    private double threshold;

    /** 触发 LLM 评估的 Reflection 分数下限（低于此值直接判定为不通过，不走 LLM） */
    @Value("${router.quality-evaluation.reflection-lower-bound:0.5}")
    private double reflectionLowerBound;

    /** 触发 LLM 评估的 Reflection 分数上限（高于此值直接通过，不走 LLM） */
    @Value("${router.quality-evaluation.reflection-upper-bound:0.8}")
    private double reflectionUpperBound;

    @Value("${router.quality-evaluation.max-evaluation-length:6000}")
    private int maxEvaluationLength;

    public QualityEvaluationService(ModelRoutingService modelRoutingService) {
        this.modelRoutingService = modelRoutingService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 对 Agent 回复执行 LLM-as-Judge 质量评估。
     * <p>
     * 仅在反思器评分处于边界区间（lowerBound ~ upperBound）时触发，
     * 避免为明显好或明显差的回复增加不必要的 LLM 调用开销。
     * </p>
     *
     * @param question     用户原始问题
     * @param reply        Agent 回复内容
     * @param reflectScore 反思器评分（用于判断是否需要 LLM 评估）
     * @return 质量评估结果
     */
    public QualityEvaluationResult evaluate(String question, String reply, double reflectScore) {
        if (!enabled) {
            return QualityEvaluationResult.skipped();
        }

        // 仅在边界区间触发 LLM 评估
        if (reflectScore < reflectionLowerBound || reflectScore > reflectionUpperBound) {
            log.debug("[QualityEval] 反思器评分 {:.2f} 不在边界区间 [{:.2f}~{:.2f}]，跳过 LLM 评估",
                    reflectScore, reflectionLowerBound, reflectionUpperBound);
            return QualityEvaluationResult.skipped();
        }

        if (question == null || question.isBlank() || reply == null || reply.isBlank()) {
            log.warn("[QualityEval] 问题或回复为空");
            return QualityEvaluationResult.skipped();
        }

        long start = System.currentTimeMillis();
        try {
            // 截断长内容，避免上下文窗口溢出
            String trimmedQuestion = truncate(question, maxEvaluationLength / 2);
            String trimmedReply = truncate(reply, maxEvaluationLength / 2);

            String userPrompt = String.format(USER_PROMPT_TEMPLATE,
                    escapeJson(trimmedQuestion), escapeJson(trimmedReply));

            String rawResponse = modelRoutingService.call(EVALUATION_SYSTEM_PROMPT, userPrompt);

            if (rawResponse == null || rawResponse.isBlank()) {
                log.warn("[QualityEval] LLM 返回空");
                return QualityEvaluationResult.failed("LLM returned empty");
            }

            QualityEvaluationResult result = parseResponse(rawResponse);
            long elapsed = System.currentTimeMillis() - start;

            log.info("[QualityEval] ✅ 评估完成: overall={}, hallucination={}, reason=\"{}\", cost={}ms",
                    String.format("%.2f", result.getOverall()),
                    String.format("%.2f", result.getHallucination()),
                    truncate(result.getReason(), 80),
                    elapsed);

            if (result.hasHallucinationRisk(threshold)) {
                log.warn("[QualityEval] ⚠️ 检测到幻觉风险: hallucination_score={}",
                        String.format("%.2f", result.getHallucination()));
            }

            return result;

        } catch (Exception e) {
            log.warn("[QualityEval] 评估异常: {}", e.getMessage());
            return QualityEvaluationResult.failed(e.getMessage());
        }
    }

    /**
     * 解析 LLM 返回的 JSON 字符串。
     * <p>
     * 支持两种格式：(1) 裸 JSON 对象 (2) 含 ```json 代码块的 JSON。
     * 解析失败时返回评估失败结果。
     * </p>
     */
    private QualityEvaluationResult parseResponse(String rawResponse) {
        String json = extractJson(rawResponse);
        if (json == null) {
            return QualityEvaluationResult.failed("No JSON found in response");
        }

        try {
            Map<String, Object> map = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});

            double relevance = getDouble(map, "relevance", 0.5);
            double completeness = getDouble(map, "completeness", 0.5);
            double hallucination = getDouble(map, "hallucination", 1.0);
            double helpfulness = getDouble(map, "helpfulness", 0.5);
            double overall = getDouble(map, "overall", 0.5);
            String reason = getString(map, "reason", "No reason provided");

            return new QualityEvaluationResult(relevance, completeness,
                    hallucination, helpfulness, overall, reason, rawResponse);

        } catch (Exception e) {
            log.warn("[QualityEval] JSON 解析失败: {}", e.getMessage());
            log.debug("[QualityEval] 原始响应: {}", truncate(rawResponse, 500));
            return QualityEvaluationResult.failed("Parse error: " + e.getMessage());
        }
    }

    /** 从 LLM 响应中提取 JSON */
    private String extractJson(String response) {
        if (response == null || response.isBlank()) return null;

        // 1. 尝试 ```json ... ```
        Pattern codeBlockPattern = Pattern.compile(
                "```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```", Pattern.CASE_INSENSITIVE);
        Matcher codeMatcher = codeBlockPattern.matcher(response);
        if (codeMatcher.find()) {
            String candidate = codeMatcher.group(1).trim();
            if (candidate.startsWith("{")) return candidate;
        }

        // 2. 尝试裸 JSON { ... }
        int braceStart = response.indexOf("{");
        if (braceStart >= 0) {
            int depth = 0;
            for (int i = braceStart; i < response.length(); i++) {
                char c = response.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return response.substring(braceStart, i + 1);
                }
            }
        }
        return null;
    }

    private double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v != null ? v.toString() : defaultValue;
    }

    private static String truncate(String str, int max) {
        if (str == null) return "";
        return str.length() > max ? str.substring(0, max) + "..." : str;
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
