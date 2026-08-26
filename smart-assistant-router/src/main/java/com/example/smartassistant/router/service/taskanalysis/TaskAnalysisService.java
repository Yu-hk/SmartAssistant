/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.common.skill.SkillPackageManager;
import com.example.smartassistant.common.skill.SkillSelectionContext;
import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.example.smartassistant.router.service.agent.AgentPromptCatalogService;
import com.example.smartassistant.router.service.core.ModelRoutingService;
import com.example.smartassistant.router.service.evaluation.IntentEvaluationService;
import com.example.smartassistant.router.service.prompt.RouterStageAwareService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * 核心流程：system prompt + 用户问题 → 按长度选择 DeepSeek 模型
 * → 意图拆解与 Agent 节点分配 → TaskAnalysisResult。
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
    private static final String TASK_PLANNER_PROMPT = "prompts/router/task-planner.txt";
    private static final String NACOS_AGENT_CATALOG_PLACEHOLDER = "{{NACOS_AGENT_CATALOG}}";
    private static final String LOCAL_FALLBACK_AGENT_CATALOG = "- route_name=general; source=local; "
            + "service_name=router-local; capabilities=[通用问答]; examples=[\"回答通用问题\"]";
    private static final String FALLBACK_PROMPT = "你是任务规划专家。仅输出合法 JSON，"
            + "将用户目标拆为至少一个原子 sub_intents 节点，并明确依赖、Agent、读写属性和完成标准。";

    private final ModelRoutingService modelRoutingService;
    private final IntentEvaluationService intentEvaluationService;
    private final ObjectMapper objectMapper;
    /** 意图向量检索器：动态检索与用户问题最相关的意图定义 */
    private final IntentRetriever intentRetriever;
    /** P2 对话阶段感知 Prompt */
    private final RouterStageAwareService stageAwareService;
    /** Nacos 健康 Agent 能力快照，调用模型前动态注入。 */
    private final AgentPromptCatalogService agentPromptCatalogService;

    /** 可信的本地版本化规划技能；不从 Nacos metadata 直接加载指令。 */
    @Autowired(required = false)
    private SkillPackageManager skillPackageManager;

    /**
     * 任务分析 prompt，支持通过 Nacos Config 动态刷新（@RefreshScope）。
     * 可将此属性配置到 Nacos 配置中心，修改后立即生效，无需重启。
     */
    @Value("${router.task-analysis.system-prompt:}")
    private String systemPrompt;

    /*
     * The previous inline prompt intentionally remains configurable through Nacos,
     * while the maintained default now lives in prompts/router/task-planner.txt.
     */
    private volatile String defaultSystemPrompt;

    /*
     * Keep the following method close to the configurable field so prompt precedence
     * is explicit: Nacos override first, checked-in resource second, safe fallback last.
     */
    private String resolveSystemPrompt() {
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            return systemPrompt;
        }
        String cached = defaultSystemPrompt;
        if (cached != null) return cached;
        synchronized (this) {
            if (defaultSystemPrompt != null) return defaultSystemPrompt;
            try (InputStream input = new ClassPathResource(TASK_PLANNER_PROMPT).getInputStream()) {
                defaultSystemPrompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception error) {
                log.error("[TaskAnalysis] 无法加载任务规划提示词资源: {}", error.getMessage());
                defaultSystemPrompt = FALLBACK_PROMPT;
            }
            return defaultSystemPrompt;
        }
    }

    @Value("${router.task-analysis.enabled:true}")
    private boolean enabled;

    @Value("${router.task-analysis.max-entities-entries:20}")
    private int maxEntityEntries;

    public TaskAnalysisService(ModelRoutingService modelRoutingService,
                               IntentEvaluationService intentEvaluationService,
                               IntentRetriever intentRetriever,
                               RouterStageAwareService stageAwareService,
                               AgentPromptCatalogService agentPromptCatalogService) {
        this.modelRoutingService = modelRoutingService;
        this.intentEvaluationService = intentEvaluationService;
        this.intentRetriever = intentRetriever;
        this.stageAwareService = stageAwareService;
        this.agentPromptCatalogService = agentPromptCatalogService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 对用户问题执行任务分析（无对话历史，兼容旧调用）。
     *
     * @param question 用户原始问题
     * @return 结构化分析结果；LLM 调用或解析失败时返回空结果，不抛异常
     */
    public TaskAnalysisResult analyze(String question) {
        return analyze(question, Collections.emptyList());
    }

    /**
     * 对用户问题执行任务分析（带多轮对话历史，P0 多轮上下文注入）。
     *
     * <p>将最近 N 轮对话历史注入 LLM Prompt，显著提升多轮指代场景的意图识别准确率。
     * 对话历史格式：每条为 "用户：xxx" 或 "助手：xxx"，按时间正序排列。</p>
     *
     * @param question            用户原始问题
     * @param conversationHistory 多轮对话历史（最近 N 条，正序）
     * @return 结构化分析结果；LLM 调用或解析失败时返回空结果，不抛异常
     */
    public TaskAnalysisResult analyze(String question, List<String> conversationHistory) {
        return analyze(question, question, conversationHistory);
    }

    /**
     * 对 RAG 增强文本做分析，但始终以用户原始提问长度选择模型。
     */
    public TaskAnalysisResult analyze(String question, String modelSelectionQuestion,
                                      List<String> conversationHistory) {
        if (!enabled || question == null || question.isBlank()) {
            return TaskAnalysisResult.empty();
        }

        long start = System.currentTimeMillis();
        try {
            // ⭐ P2 对话阶段感知：推断当前阶段，注入聚焦指令
            int turnCount = conversationHistory != null ? (conversationHistory.size() / 2) + 1 : 1;
            var stage = stageAwareService.inferStage(turnCount, null,
                    conversationHistory != null && !conversationHistory.isEmpty()
                            ? conversationHistory.get(conversationHistory.size() - 1) : null);

            // ⭐ 动态构建 prompt：检索与用户问题最相关的意图定义，替换全量硬编码
            //     多轮场景：注入对话历史，提升指代消解和意图连贯性
            String basePrompt = buildDynamicPrompt(question, conversationHistory);
            String finalPrompt = stageAwareService.wrapPrompt(basePrompt, stage);
            ModelRoutingService.IntentModelResponse modelResponse = modelRoutingService.callForIntent(
                    finalPrompt, buildUserMessage(question, conversationHistory),
                    modelSelectionQuestion);
            String rawResponse = modelResponse.content();

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
            result.setAnalysisModel(modelResponse.modelName());
            result.setAnalysisModelTier(modelResponse.modelTier());
            result.setAnalysisQuestionChars(modelResponse.questionChars());
            result.setAnalysisLatencyMs(modelResponse.elapsedMs());

            // 规则层后处理：实体归一化、词槽状态、澄清判断、输入鲁棒性
            if (result.isMeaningful() && intentEvaluationService != null) {
                result = intentEvaluationService.postProcess(question, result);
            }

            long elapsed = System.currentTimeMillis() - start;

            if (result.isMeaningful()) {
                log.info("[TaskAnalysis] ✅ 分析完成: intent={}, model={}/{}, entities={}, subIntents={}, implicitIntents={}, constraints={}, risks={}, slots=[filled={},missing={},conflicts={}], cost={}ms",
                        result.getIntentCategory(),
                        result.getAnalysisModelTier(),
                        result.getAnalysisModel(),
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
     * 动态构建任务分析 prompt（无对话历史，兼容内部调用）。
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
        return buildDynamicPrompt(question, Collections.emptyList());
    }

    /**
     * 动态构建任务分析 prompt（带多轮对话历史）。
     *
     * <p>System Prompt 本身不携带对话历史（避免过长），对话历史通过
     * {@link #buildUserMessage(String, List)} 注入 User Message。
     * 此方法保留意图检索增强逻辑，与无历史版本行为一致。</p>
     *
     * @param question            用户问题
     * @param conversationHistory 多轮对话历史（未使用，保留参数以便后续扩展）
     * @return 构建完成的 system prompt 文本
     */
    private String buildDynamicPrompt(String question, List<String> conversationHistory) {
        String basePrompt = injectDiscoveredAgents(resolveSystemPrompt());
        if (intentRetriever == null) {
            return appendRouterSkills(basePrompt, question, List.of());
        }
        try {
            List<IntentDef> relevant = intentRetriever.retrieve(question, 3);
            basePrompt = appendRouterSkills(basePrompt, question, relevant);
            String intentSection = intentRetriever.buildIntentSection(relevant);
            if (intentSection == null) {
                return basePrompt;
            }
            return basePrompt + "\n\n" + intentSection;
        } catch (Exception e) {
            log.warn("[TaskAnalysis] Dynamic intent retrieval failed, using base prompt: {}", e.getMessage());
            return basePrompt;
        }
    }

    private String appendRouterSkills(String basePrompt, String question, List<IntentDef> intents) {
        if (skillPackageManager == null) return basePrompt;
        // Skill 选择只采用语义检索 Top-1，避免 Top-3 中的低相关意图导致能力膨胀；
        // COMPLEX 技能通过 dependencies 显式带入跨域规则。
        List<String> intentIds = intents == null ? List.of() : intents.stream()
                .limit(1)
                .map(IntentDef::id)
                .toList();
        SkillSelectionContext context = SkillSelectionContext.builder()
                .query(question)
                .operations(intentIds)
                .maxSkills(4)
                .build();
        String skillPrompt = skillPackageManager.buildAgentSkillPrompt("router-service", context);
        if (skillPrompt.isBlank()) return basePrompt;
        log.info("[TaskAnalysis] 本次激活规划技能: {}",
                skillPackageManager.selectAgentSkills("router-service", context).stream()
                        .map(skill -> skill.getId() + "@" + skill.getVersion())
                        .toList());
        return basePrompt + "\n" + skillPrompt;
    }

    /**
     * Injects the current healthy Agent catalog even when the base prompt is overridden
     * through Nacos Config and therefore does not contain the checked-in placeholder.
     */
    private String injectDiscoveredAgents(String basePrompt) {
        String catalog;
        try {
            catalog = agentPromptCatalogService != null
                    ? agentPromptCatalogService.buildCatalog()
                    : LOCAL_FALLBACK_AGENT_CATALOG;
        } catch (Exception error) {
            log.warn("[TaskAnalysis] Nacos Agent catalog unavailable, using local fallback: {}",
                    error.getMessage());
            catalog = LOCAL_FALLBACK_AGENT_CATALOG;
        }

        if (basePrompt.contains(NACOS_AGENT_CATALOG_PLACEHOLDER)) {
            return basePrompt.replace(NACOS_AGENT_CATALOG_PLACEHOLDER, catalog);
        }
        return basePrompt + "\n\n## 本次请求可用 Agent（Router 从 Nacos 动态注入）\n" + catalog;
    }

    /**
     * 构建注入多轮对话历史的 User Message。
     *
     * <p>将最近 N 轮对话历史以结构化格式拼接到当前问题前，
     * 帮助 LLM 理解上下文依赖和指代关系（"它"、"这个"等）。</p>
     *
     * <p>格式示例：
     * <pre>
     * ## 对话历史
     * 用户：我想查一下我的订单
     * 助手：您的订单 ON20240601001 已发货，物流公司：顺丰
     * 用户：它什么时候到
     *
     * ## 当前问题
     * 它什么时候到
     * </pre>
     * </p>
     *
     * @param question            当前用户问题
     * @param conversationHistory 对话历史（最近 N 条，正序）
     * @return 完整的 User Message
     */
    private String buildUserMessage(String question, List<String> conversationHistory) {
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return question;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 对话历史（最近").append(conversationHistory.size()).append("轮）\n");
        for (String msg : conversationHistory) {
            sb.append(msg).append("\n");
        }
        sb.append("\n## 当前问题\n");
        sb.append(question);
        return sb.toString();
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

            if (map.get("task_steps") instanceof List<?> values) {
                result.setTaskSteps(toStringList(values));
            }
            if (map.get("execution_order") instanceof List<?> values) {
                result.setExecutionOrder(toStringList(values));
            }
            if (map.get("flowchart") != null) {
                String flowchart = String.valueOf(map.get("flowchart"));
                if (!flowchart.isBlank() && !"null".equalsIgnoreCase(flowchart)) {
                    result.setFlowchart(flowchart);
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
                if (!subIntents.isEmpty()) {
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

    private static List<String> toStringList(List<?> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static String truncate(String str, int max) {
        if (str == null) return "";
        return str.length() > max ? str.substring(0, max) + "..." : str;
    }
}
