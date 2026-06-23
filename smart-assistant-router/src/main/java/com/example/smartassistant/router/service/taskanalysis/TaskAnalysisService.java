/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.example.smartassistant.router.service.core.ModelRoutingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务分析服务——将用户的模糊自然语言请求转化为结构化信息。
 * <p>
 * 参考 ThinkingAgent 的设计：在接受用户请求后、路由决策之前，
 * 对请求进行结构化分析，提取实体、约束、风险标记和工具相关性评分。
 * </p>
 * <p>
 * 核心流程：system prompt + 用户问题 → Ollama deepseek-r1 → 解析 JSON 响应 → TaskAnalysisResult
 * </p>
 * <p>
 * 降级策略：LLM 调用失败或 JSON 解析失败时返回空的 TaskAnalysisResult，
 * 不阻塞路由主流程。
 * </p>
 *
 * @see TaskAnalysisResult
 * @see ModelRoutingService
 */
@Service
public class TaskAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TaskAnalysisService.class);

    private final ModelRoutingService modelRoutingService;
    private final ObjectMapper objectMapper;

    /**
     * 任务分析 prompt 模板。
     * 要求 LLM 输出严格 JSON，包含实体/约束/风险/工具评分等信息。
     */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一个电商客服系统的任务分析器。
            分析用户的提问，提取结构化信息。

            输出要求：
            1. 仅输出一个合法的 JSON 对象，不要包含任何其他文字、markdown 代码块标记或说明。
            2. JSON 必须包含以下所有字段：

            {
              "intent_category": "意图分类，仅限 ORDER(订单/物流/退款)/PRODUCT(商品查询/库存/价格)/GENERAL(问答/计算/天气/新闻)/COMPLEX(跨领域)/UNKNOWN",
              "entities": {
                "order_id": "订单号或null",
                "product_name": "商品名称或null",
                "date": "日期信息或null",
                "amount": "金额信息或null",
                "location": "地点或null",
                "currency": "币种或null"
              },
              "action_constraints": ["行为约束列表，如'仅查询''勿修改订单'"],
              "output_constraints": ["输出约束列表，如'Markdown格式''200字以内'"],
              "risk_flags": ["风险标记列表，如'涉及退款''需二次确认''数据敏感'"],
              "task_goal": "一句话概括用户任务目标（≤20字）",
              "tool_scores": {
                "query_order": 0.0-1.0,
                "pay_order": 0.0-1.0,
                "cancel_order": 0.0-1.0,
                "query_product": 0.0-1.0,
                "check_stock": 0.0-1.0,
                "getHotNews": 0.0-1.0,
                "calculate": 0.0-1.0,
                "convertCurrency": 0.0-1.0,
                "searchWeb": 0.0-1.0
              }
            }

            评分规则：
            - 0.0: 完全不相关
            - 0.1~0.3: 边缘相关
            - 0.4~0.6: 中等相关
            - 0.7~1.0: 高度相关或必须使用

            覆核要求：实体字段用 null 而非空字符串表示不存在。
            """;

    @Value("${router.task-analysis.enabled:true}")
    private boolean enabled;

    @Value("${router.task-analysis.max-entities-entries:20}")
    private int maxEntityEntries;

    public TaskAnalysisService(ModelRoutingService modelRoutingService) {
        this.modelRoutingService = modelRoutingService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 对用户问题执行任务分析。
     *
     * @param question 用户原始问题
     * @return 结构化分析结果；LLM 调用或解析失败时返回空结果，不抛异常
     */
    public TaskAnalysisResult analyze(String question) {
        if (!enabled || question == null || question.isBlank()) {
            return TaskAnalysisResult.empty();
        }

        long start = System.currentTimeMillis();
        try {
            String rawResponse = modelRoutingService.call(SYSTEM_PROMPT_TEMPLATE, question);

            if (rawResponse == null || rawResponse.isBlank()) {
                log.warn("[TaskAnalysis] LLM 返回空响应");
                return TaskAnalysisResult.empty();
            }

            String json = extractJson(rawResponse);
            if (json == null) {
                log.warn("[TaskAnalysis] 未能从 LLM 响应中提取 JSON, rawResponse={}", truncate(rawResponse, 200));
                return TaskAnalysisResult.empty();
            }

            TaskAnalysisResult result = parseJson(json);
            long elapsed = System.currentTimeMillis() - start;

            if (result.isMeaningful()) {
                log.info("[TaskAnalysis] ✅ 分析完成: intent={}, entities={}, constraints={}, risks={}, toolScores={}, cost={}ms",
                        result.getIntentCategory(),
                        result.getEntities().size(),
                        result.getActionConstraints().size(),
                        result.getRiskFlags().size(),
                        result.getToolScores().size(),
                        elapsed);
            } else {
                log.info("[TaskAnalysis] ℹ️ 分析完成(结果为空): cost={}ms", elapsed);
            }
            return result;

        } catch (Exception e) {
            log.warn("[TaskAnalysis] 分析异常: {}", e.getMessage());
            return TaskAnalysisResult.empty();
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 字符串。
     * <p>
     * 优先查找 ```json ... ``` 代码块；其次尝试查找最外层的 { ... }。
     * deepseek-r1 模型可能输出思考过程（在 <｜end▁of▁thinking｜>... 标记之间），
     * 此方法会忽略非 JSON 内容。
     */
    private String extractJson(String response) {
        if (response == null || response.isBlank()) return null;

        // 1. 尝试提取 ```json ... ``` 代码块
        Pattern codeBlockPattern = Pattern.compile(
                "(?:```json|```)\\s*\\n?([\\s\\S]*?)\\n?\\s*```", Pattern.CASE_INSENSITIVE);
        Matcher codeMatcher = codeBlockPattern.matcher(response);
        if (codeMatcher.find()) {
            String candidate = codeMatcher.group(1).trim();
            if (candidate.startsWith("{")) {
                return candidate;
            }
        }

        // 2. 尝试提取无标记的最外层 { ... }
        //    跳过可能的思考过程（response...之前的部分）
        int thinkEnd = response.lastIndexOf("</thinking>");
        int searchStart = (thinkEnd >= 0) ? thinkEnd + "</thinking>".length() : 0;

        int braceStart = response.indexOf("{", searchStart);
        if (braceStart < 0) {
            braceStart = response.indexOf("{");
        }
        if (braceStart >= 0) {
            int braceDepth = 0;
            int jsonEnd = -1;
            for (int i = braceStart; i < response.length(); i++) {
                char c = response.charAt(i);
                if (c == '{') braceDepth++;
                else if (c == '}') {
                    braceDepth--;
                    if (braceDepth == 0) {
                        jsonEnd = i;
                        break;
                    }
                }
            }
            if (jsonEnd > braceStart) {
                return response.substring(braceStart, jsonEnd + 1);
            }
        }

        return null;
    }

    /**
     * 将 JSON 字符串解析为 TaskAnalysisResult。
     * <p>
     * 先将 JSON 解析为通用 Map，再逐一提取各字段。
     * 这样即使 LLM 输出了额外字段也能容错。
     */
    private TaskAnalysisResult parseJson(String json) {
        TaskAnalysisResult result = new TaskAnalysisResult();
        try {
            Map<String, Object> map = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});

            // intent_category
            if (map.containsKey("intent_category")) {
                result.setIntentCategory(String.valueOf(map.get("intent_category")));
            }

            // entities
            if (map.containsKey("entities") && map.get("entities") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> entities = (Map<String, Object>) map.get("entities");
                Map<String, Object> cleaned = new LinkedHashMap<>();
                int count = 0;
                for (Map.Entry<String, Object> e : entities.entrySet()) {
                    if (count >= maxEntityEntries) break;
                    Object val = e.getValue();
                    if (val != null && !"null".equals(val.toString())
                            && !val.toString().isBlank()) {
                        cleaned.put(e.getKey(), val);
                        count++;
                    }
                }
                result.setEntities(cleaned);
            }

            // action_constraints
            if (map.containsKey("action_constraints") && map.get("action_constraints") instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> constraints = (List<String>) map.get("action_constraints");
                result.setActionConstraints(constraints);
            }

            // output_constraints
            if (map.containsKey("output_constraints") && map.get("output_constraints") instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> outputConstraints = (List<String>) map.get("output_constraints");
                result.setOutputConstraints(outputConstraints);
            }

            // risk_flags
            if (map.containsKey("risk_flags") && map.get("risk_flags") instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> risks = (List<String>) map.get("risk_flags");
                result.setRiskFlags(risks);
            }

            // task_goal
            if (map.containsKey("task_goal")) {
                String goal = String.valueOf(map.get("task_goal"));
                if (!goal.isBlank() && !"null".equals(goal)) {
                    result.setTaskGoal(goal);
                }
            }

            // tool_scores
            if (map.containsKey("tool_scores") && map.get("tool_scores") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rawScores = (Map<String, Object>) map.get("tool_scores");
                Map<String, Double> scores = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : rawScores.entrySet()) {
                    try {
                        double v = Double.parseDouble(String.valueOf(e.getValue()));
                        if (v > 0) {
                            scores.put(e.getKey(), v);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                result.setToolScores(scores);
            }

        } catch (Exception e) {
            log.warn("[TaskAnalysis] JSON 解析失败: {}", e.getMessage());
            log.debug("[TaskAnalysis] 原始 JSON: {}", truncate(json, 500));
            return TaskAnalysisResult.empty();
        }
        return result;
    }

    private static String truncate(String str, int max) {
        if (str == null) return "";
        return str.length() > max ? str.substring(0, max) + "..." : str;
    }
}
