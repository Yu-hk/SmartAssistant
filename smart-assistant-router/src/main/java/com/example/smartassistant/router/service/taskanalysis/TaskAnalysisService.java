/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.example.smartassistant.router.service.core.ModelRoutingService;
import com.example.smartassistant.router.service.evaluation.IntentEvaluationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
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
@RefreshScope
public class TaskAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TaskAnalysisService.class);

    private final ModelRoutingService modelRoutingService;
    private final IntentEvaluationService intentEvaluationService;
    private final ObjectMapper objectMapper;
    /** 意图向量检索器：动态检索与用户问题最相关的意图定义 */
    private final IntentRetriever intentRetriever;

    /**
     * 任务分析 prompt，支持通过 Nacos Config 动态刷新（@RefreshScope）。
     * 可将此属性配置到 Nacos 配置中心，修改后立即生效，无需重启。
     */
    @Value("${router.task-analysis.system-prompt:"
            + "你是一个电商客服系统的任务分析器。\n"
            + "分析用户的提问，提取结构化信息。\n\n"
            + "输出要求：\n"
            + "1. 仅输出一个合法的 JSON 对象，不要包含任何其他文字、markdown 代码块标记或说明。\n"
            + "2. JSON 必须包含以下所有字段：\n\n"
            + "{\n"
            + "  \"intent_category\": \"意图分类，仅限 ORDER(订单/物流/退款)/PRODUCT(商品查询/库存/价格)/GENERAL(问答/计算/天气/新闻)/COMPLEX(跨领域)/UNKNOWN\",\n"
            + "  \"confidence\": \"意图分类置信度 0.0~1.0，根据用户输入清晰度和匹配度评估\",\n"
            + "  \"entities\": {\n"
            + "    \"order_id\": \"订单号或null\",\n"
            + "    \"product_name\": \"商品名称或null\",\n"
            + "    \"date\": \"日期信息或null\",\n"
            + "    \"amount\": \"金额信息或null\",\n"
            + "    \"location\": \"地点或null\",\n"
            + "    \"currency\": \"币种或null\",\n"
            + "    \"departure_station\": \"出发站（火车票场景）或null\",\n"
            + "    \"arrival_station\": \"到达站或null\",\n"
            + "    \"departure_time\": \"出发时间或null\",\n"
            + "    \"passenger\": \"乘客姓名或null\",\n"
            + "    \"seat_type\": \"座位类型（二等座/一等座/商务座）或null\",\n"
            + "    \"train_type\": \"车次类型（高铁/动车/普快）或null\",\n"
            + "    \"departure_date\": \"出发日期或null\"\n"
            + "  },\n"
            + "  \"sub_intents\": [\n"
            + "    {\n"
            + "      \"intent\": \"子意图分类（查票/预订/改签/退票/订单查询/查商品/看天气等）\",\n"
            + "      \"description\": \"子任务描述\",\n"
            + "      \"depends_on\": \"依赖的子任务（如'查票'后才有'下单'，填null表示无依赖）\",\n"
            + "      \"order\": 1\n"
            + "    }\n"
            + "  ],\n"
            + "  \"implicit_intents\": [\n"
            + "    {\n"
            + "      \"expression\": \"用户的表层表达\",\n"
            + "      \"inferred_intent\": \"推断的真实目标\",\n"
            + "      \"confidence\": \"confidence 0.0~1.0\",\n"
            + "      \"trigger_basis\": \"触发依据（业务语境/对话状态/业务规则）\"\n"
            + "    }\n"
            + "  ],\n"
            + "  \"action_constraints\": [\"行为约束列表，如'仅查询''勿修改订单'\"],\n"
            + "  \"output_constraints\": [\"输出约束列表，如'Markdown格式''200字以内'\"],\n"
            + "  \"risk_flags\": [\"风险标记列表，如'涉及退款''需二次确认''数据敏感''交易越界'\"],\n"
            + "  \"task_goal\": \"一句话概括用户任务目标（≤20字）\",\n"
            + "  \"tool_scores\": {\n"
            + "    \"query_order\": 0.0-1.0,\n"
            + "    \"pay_order\": 0.0-1.0,\n"
            + "    \"cancel_order\": 0.0-1.0,\n"
            + "    \"query_product\": 0.0-1.0,\n"
            + "    \"check_stock\": 0.0-1.0,\n"
            + "    \"getHotNews\": 0.0-1.0,\n"
            + "    \"calculate\": 0.0-1.0,\n"
            + "    \"convertCurrency\": 0.0-1.0,\n"
            + "    \"searchWeb\": 0.0-1.0\n"
            + "  }\n"
            + "}\n\n"
            + "评分规则：\n"
            + "- 0.0: 完全不相关\n"
            + "- 0.1~0.3: 边缘相关\n"
            + "- 0.4~0.6: 中等相关\n"
            + "- 0.7~1.0: 高度相关或必须使用\n\n"
            + "多意图拆分说明：\n"
            + "- 如果一句话包含多个任务（如\"查明天去上海的票，有合适的就订\"），务必拆到 sub_intents 数组\n"
            + "- depends_on 体现任务依赖关系（先查票，再下单）\n"
            + "- order 字段标记执行顺序（1最先）\n\n"
            + "隐含意图说明：\n"
            + "- 用户没直说但有上下文提示的目标，补到 implicit_intents\n"
            + "- 例如用户说\"别耽误四点的会\"，隐含意图是\"到达时间需早于16点，预留出站缓冲\"\n"
            + "- trigger_basis 必须来自：业务语境/对话状态/业务规则\n\n"
            + "拒识说明：\n"
            + "- 如果用户请求越界、不合规、无效（如\"买昨天票\"、\"绕过实名\"），在 risk_flags 中标出\n"
            + "- 拒识原因加入 action_constraints：\"无效请求-原因\"/\"越界请求-原因\"/\"不支持-原因\"\n\n"
            + "覆核要求：实体字段用 null 而非空字符串表示不存在。"
            + "}")
    private String systemPrompt;

    @Value("${router.task-analysis.enabled:true}")
    private boolean enabled;

    @Value("${router.task-analysis.max-entities-entries:20}")
    private int maxEntityEntries;

    public TaskAnalysisService(ModelRoutingService modelRoutingService,
                               IntentEvaluationService intentEvaluationService,
                               IntentRetriever intentRetriever) {
        this.modelRoutingService = modelRoutingService;
        this.intentEvaluationService = intentEvaluationService;
        this.intentRetriever = intentRetriever;
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
            // ⭐ 动态构建 prompt：检索与用户问题最相关的意图定义，替换全量硬编码
            String finalPrompt = buildDynamicPrompt(question);
            String rawResponse = modelRoutingService.call(finalPrompt, question);

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

            // 规则层后处理：实体归一化、词槽状态、澄清判断、输入鲁棒性
            if (result.isMeaningful() && intentEvaluationService != null) {
                result = intentEvaluationService.postProcess(question, result);
            }

            long elapsed = System.currentTimeMillis() - start;

            if (result.isMeaningful()) {
                log.info("[TaskAnalysis] ✅ 分析完成: intent={}, entities={}, subIntents={}, implicitIntents={}, constraints={}, risks={}, slots=[filled={},missing={},conflicts={}], cost={}ms",
                        result.getIntentCategory(),
                        result.getEntities().size(),
                        result.getSubIntents().size(),
                        result.getImplicitIntents().size(),
                        result.getActionConstraints().size(),
                        result.getRiskFlags().size(),
                        result.getFilledSlots().size(),
                        result.getMissingSlots().size(),
                        result.getSlotConflicts().size(),
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
     * 动态构建任务分析 prompt。
     *
     * <p>在基础 system prompt 后追加通过 {@link IntentRetriever} 检索到的与当前问题
     * 最相关的意图定义（Top-3），而非将所有 5 个意图定义全量硬编码在 prompt 中。
     * 这使 LLM 专注于相关意图，减少干扰，同时简化后续新增意图的维护成本。</p>
     *
     * <p>降级策略：意图检索器不可用或未命中时，直接使用基础 prompt（包含全量意图定义）。</p>
     *
     * @param question 用户问题
     * @return 构建完成的 prompt 文本
     */
    private String buildDynamicPrompt(String question) {
        if (intentRetriever == null) {
            return systemPrompt;
        }
        try {
            List<IntentDef> relevant = intentRetriever.retrieve(question, 3);
            String intentSection = intentRetriever.buildIntentSection(relevant);
            if (intentSection == null) {
                return systemPrompt;
            }
            return systemPrompt + "\n\n" + intentSection;
        } catch (Exception e) {
            log.warn("[TaskAnalysis] 动态意图检索失败，使用全量定义: {}", e.getMessage());
            return systemPrompt;
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

            // confidence
            if (map.containsKey("confidence")) {
                try {
                    double conf = Double.parseDouble(String.valueOf(map.get("confidence")));
                    result.setConfidence(Math.max(0.0, Math.min(1.0, conf)));
                } catch (NumberFormatException ignored) {}
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

            // ---------- 新增字段解析 ----------

            // sub_intents（多意图拆分）
            if (map.containsKey("sub_intents") && map.get("sub_intents") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> subIntents = (List<Map<String, Object>>) map.get("sub_intents");
                if (subIntents.size() > 1) { // 只有多个意图才保留
                    result.setSubIntents(subIntents);
                }
            }

            // implicit_intents（隐含意图）
            if (map.containsKey("implicit_intents") && map.get("implicit_intents") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> implicitIntents =
                        (List<Map<String, Object>>) map.get("implicit_intents");
                result.setImplicitIntents(implicitIntents);
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
