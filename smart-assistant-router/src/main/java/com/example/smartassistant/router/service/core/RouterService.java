/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.agent.ExecutionTraceStore;
import com.example.smartassistant.common.agent.AgentExecutionState;
import com.example.smartassistant.common.agent.FeedbackLog;
import com.example.smartassistant.common.agent.GoalContinuityArbiter;
import com.example.smartassistant.common.budget.BudgetTracker;
import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.intent.IntentTagGenerator;
import com.example.smartassistant.router.service.context.IntentDriftDetector;
import com.example.smartassistant.common.error.ErrorRecoveryService;
import com.example.smartassistant.router.model.*;
import com.example.smartassistant.router.service.monitoring.NewMetricsCollector;
import com.example.smartassistant.router.service.evaluation.IntentGuidedQueryRewriter;
import com.example.smartassistant.common.observability.OpsMetrics;
import com.example.smartassistant.router.service.guardrail.EmotionCheckResult;
import com.example.smartassistant.router.service.guardrail.EmotionLevel;
import com.example.smartassistant.router.service.guardrail.GuardrailService;
import com.example.smartassistant.router.service.rag.RouterRagService;
import com.example.smartassistant.router.service.taskanalysis.TaskAnalysisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Router Service - DeepSeek 结构化意图分析与 LangGraph4j 协作入口。
 *
 * <p>职责范围：</p>
 * <ul>
 *     <li>按问题长度选择 DeepSeek 模型</li>
 *     <li>结构化拆解意图、依赖与目标 Agent</li>
 *     <li>将分析结果交给 LangGraph4j 执行</li>
 * </ul>
 *
 * <p>已移除职责（迁移到 Consumer）：</p>
 * <ul>
 *     <li>日常建议生成 → Consumer.LLMSuggestionService</li>
 * </ul>
 * <p>关键词快车道、经验匹配和 Consumer 单 Agent 提示不再参与路由决策，
 * 避免优化路径吞掉跨领域任务。</p>
 */
@Service
public class RouterService {
    
    private static final Logger log = LoggerFactory.getLogger(RouterService.class);
    
    private final StringRedisTemplate redisTemplate;
    private final RouterRagService ragService;
    private final IntentTagGenerator intentTagGenerator;

    // ⭐ 任务分析服务（结构化提取实体/约束/风险/工具评分）
    private final TaskAnalysisService taskAnalysisService;

    // ⭐ 意图引导的查询改写服务
    private final IntentGuidedQueryRewriter queryRewriter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** ⭐ G4 运营指标收集器（路由延迟/接管/应答），零装配、全局注册表 */
    private final OpsMetrics opsMetrics = new OpsMetrics();

    // ⭐ 并行 Agent 执行线程池（用于独立子任务的真正并行执行）

    // ⭐ 子任务单 Agent 超时（毫秒）

    @Value("${router.agent.rag.enabled:false}")
    private boolean ragEnabled;

    @Value("${router.context.history.max-messages:10}")
    private int maxHistoryMessages;

    // ⭐ P1 Agent 执行事件总线（可选：无 Redis 时不记录事件）
    @Autowired(required = false)
    private ExecutionTraceStore executionTraceStore;

    // ⭐ L5 意图漂移检测
    @Autowired(required = false)
    private IntentDriftDetector intentDriftDetector;

    // ⭐ P1 预算追踪
    @Autowired(required = false)
    private BudgetTracker budgetTracker;
    // ⭐ P2 新指标采集
    @Autowired(required = false)
    private NewMetricsCollector newMetrics;

    // ⭐ P1 确定性护栏服务
    private final GuardrailService guardrailService;

    // ⭐ 路由后处理器
    private final RouteFinalizer routeFinalizer;
    // ⭐ 协作执行器
    private final RouteExecutionService routeExecutionService;
    // ⭐ 上下文构建器
    private final RouteContextHelper routeContextHelper;

    public RouterService(@Autowired(required = false) StringRedisTemplate redisTemplate,
                         RouterRagService ragService,
                         IntentTagGenerator intentTagGenerator,
                         TaskAnalysisService taskAnalysisService,
                         IntentGuidedQueryRewriter queryRewriter,
                         GuardrailService guardrailService,
                         RouteFinalizer routeFinalizer,
                         RouteExecutionService routeExecutionService,
                         RouteContextHelper routeContextHelper) {
        this.redisTemplate = redisTemplate;
        this.ragService = ragService;
        this.intentTagGenerator = intentTagGenerator;
        this.taskAnalysisService = taskAnalysisService;
        this.queryRewriter = queryRewriter;
        this.guardrailService = guardrailService;
        this.routeFinalizer = routeFinalizer;
        this.routeExecutionService = routeExecutionService;
        this.routeContextHelper = routeContextHelper;
    }

    /**
     * 执行路由决策并调用目标 Agent（统一入口）
     * 只识别并处理第一个意图
     */
    public RoutingResult route(RouteRequest request) {
        log.info("[Router] 收到路由请求: userId={}, sessionId={}, question={}",
                request.getUserId(), request.getSessionId(), QuestionExtractor.truncate(request.getQuestion(), 100));

        // ⭐ P1 预算追踪：会话开始
        if (budgetTracker != null) {
            budgetTracker.startSession();
        }

        long routeStart = System.nanoTime();
        try {
            // Step 0: 经验匹配（优先级最高，在语义缓存之上）
            // ⭐ 经验匹配可直接跳过 LLM 推理，命中 TOOL 经验时甚至直接执行工具
            String question = request.getQuestion();

            // ⭐ P1 确定性护栏：检查高风险关键词（退款/退货/投诉等）
            GuardrailService.GuardrailCheckResult guardrail = guardrailService.check(question);
            boolean guardrailForceRag = guardrail.triggered() && guardrail.forceRag();

            // ⭐ P4-A 情绪分级干预（对标文章②）：检测用户心理安全风险
            EmotionCheckResult emotion = guardrailService.checkEmotion(question);

            // 重度风险（自伤/伤人倾向）：立即安全兜底，禁止任何工具调用，引导专业求助
            if (emotion.level() == EmotionLevel.HEAVY) {
                log.warn("[Router] 💗 重度情绪风险，进入安全兜底: userId={}, signals={}",
                        request.getUserId(), emotion.signals());
                // ⭐ G4 运营指标：重度情绪风险触发人工/专业接管
                opsMetrics.recordHandoff("emotion_heavy", "router_fallback");
                return RoutingResult.builder()
                        .result(emotion.guidance())
                        .confidence(1.0)
                        .executionMode(RoutingResult.ExecutionMode.FALLBACK)
                        .workflowStatus(RoutingResult.WorkflowStatus.DEGRADED)
                        .emotionLevel(EmotionLevel.HEAVY)
                        .emotionIntervention(true)
                        .disableTools(true)
                        .emotionGuidance(emotion.guidance())
                        .build();
            }

            if (guardrail.triggered()) {
                log.warn("[Router] 🛡️ 护栏激活: question='{}', matchedTerms={}, skipShortCircuit={}, forceRag={}",
                        QuestionExtractor.truncate(question, 50), guardrail.matchedTerms(),
                        guardrail.skipShortCircuit(), guardrail.forceRag());
            }

            // ⭐ P2 Token 配额检查：开始路由前先检查用户级日配额
            if (budgetTracker != null && request.getUserId() != null) {
                String quotaMsg = budgetTracker.checkUserQuota(request.getUserId());
                if (quotaMsg != null) {
                    log.warn("[Router] ⚠️ 用户配额超限: userId={}, msg={}", request.getUserId(), quotaMsg);
                    return RoutingResult.builder()
                            .result(quotaMsg)
                            .confidence(0.2)
                            .executionMode(RoutingResult.ExecutionMode.FALLBACK)
                            .workflowStatus(RoutingResult.WorkflowStatus.DEGRADED)
                            .build();
                }
            }

            // 普通业务意图不再执行关键词、经验或 Consumer 单 Agent 提示短路。
            // 安全护栏只负责风险控制，节点拆解和分配统一由 DeepSeek 完成。
            Map<String, Object> context = buildContext(request);

            // Step 3: RAG 增强(可选) — 正常开启或护栏触发时均执行
            String enhancedQuestion = question;
            boolean doRag = (ragEnabled && Boolean.TRUE.equals(request.getEnableRag())) || guardrailForceRag;
            if (doRag) {
                @SuppressWarnings("unchecked")
                List<String> history = (List<String>) context.get("conversationHistory");
                enhancedQuestion = ragService.enhanceQuestion(
                        request.getQuestion(),
                        history
                );
                if (guardrailForceRag) {
                    log.info("[Router] 🛡️ 护栏强制 RAG 增强完成: enhancedLength={}", enhancedQuestion.length());
                }
            }

            Long userId = request.getUserId();
            String executionQuestion = enhancedQuestion;

            RoutingResult result;
                // DeepSeek is the single source of truth for intent decomposition and node
                // assignment. TaskAnalysisService selects chat/reasoner by question length.
                @SuppressWarnings("unchecked")
                List<String> conversationHistory =
                        (List<String>) context.get("conversationHistory");
            TaskAnalysisResult taskAnalysis = taskAnalysisService.analyze(
                    enhancedQuestion, question,
                    conversationHistory != null ? conversationHistory : Collections.emptyList());
                String intentTag = taskAnalysis != null ? taskAnalysis.getIntentCategory() : null;
                double confidence = taskAnalysis != null && taskAnalysis.isMeaningful()
                        ? taskAnalysis.getConfidence() : 0.5;
                log.info("[Router] DeepSeek task analysis: intent={}, subIntents={}, confidence={}",
                        intentTag,
                        taskAnalysis != null ? taskAnalysis.getSubIntents().size() : 0,
                        confidence);

                // ⭐ L5 意图漂移检测：多轮对话中检测用户意图是否漂移
                if (intentDriftDetector != null && intentTag != null
                        && conversationHistory != null && !conversationHistory.isEmpty()) {
                    var drift = intentDriftDetector.detect(enhancedQuestion, conversationHistory);
                    if (drift.driftDetected()) {
                        log.warn("[Router] 🔄 意图漂移: from='{}' to='{}', similarity={}, strong={}",
                                QuestionExtractor.truncate(drift.previousQuestion(), 30),
                                intentTag,
                                String.format("%.4f", drift.similarity()),
                                drift.strongDrift());
                        // 强漂移时标记 intentTag（下游可据此重置上下文）
                        if (drift.strongDrift()) {
                            log.info("[Router] 🔄 强漂移检测: 新意图={}, 上下文需重置", intentTag);
                        }
                    }
                }

                // ⭐ 文章⑥目标连续性裁决：BGE 漂移之外增加词汇重叠度 + 二级裁决
                if (conversationHistory != null && !conversationHistory.isEmpty()) {
                    String lastUserQuestion = QuestionExtractor.extractLastUserQuestion(conversationHistory);
                    if (lastUserQuestion != null) {
                        GoalContinuityArbiter arbiter = new GoalContinuityArbiter();
                        var goalResult = arbiter.arbitrate(enhancedQuestion, lastUserQuestion);
                        if (goalResult.arbiterLevel() == GoalContinuityArbiter.ArbiterLevel.FUZZY) {
                            log.info("[Router] 🔄 目标连续性模糊: overlap={}, 需用户确认",
                                    String.format("%.2f", goalResult.overlapScore()));
                        } else if (!goalResult.sameTask()) {
                            log.info("[Router] 🔄 目标连续性判定为新任务: reason={}, overlap={}",
                                    goalResult.reason(), String.format("%.2f", goalResult.overlapScore()));
                            // 新任务 → 标记上下文需重置
                            intentTag = intentTag != null ? intentTag + "_NEW" : "NEW_TASK";
                        }
                    }
                }

                if (taskAnalysis != null && taskAnalysis.isMeaningful()) {
                    storeTaskAnalysisToRedis(request.getRequestId(), taskAnalysis);
                }

                if (shouldShortCircuitForClarification(taskAnalysis)) {
                    String clarificationReply = String.join("\n", taskAnalysis.getClarificationQuestions());
                    String clarificationAgent = declaredClarificationAgent(taskAnalysis);
                    RoutingResult clarification = RoutingResult.builder()
                            .result(clarificationReply)
                            .agentName(clarificationAgent)
                            .executionMode(clarificationAgent != null
                                    ? RoutingResult.ExecutionMode.SINGLE_AGENT
                                    : RoutingResult.ExecutionMode.BUILTIN)
                            .participatingAgents(clarificationAgent != null
                                    ? List.of(clarificationAgent) : List.of())
                            .workflowStatus(RoutingResult.WorkflowStatus.CLARIFICATION)
                            .confidence(taskAnalysis.getConfidence())
                            .intentTag(intentTag)
                            .clarification(true)
                            .build();
                    return finalizeRouting(clarification, request, enhancedQuestion, emotion);
                }
                if (taskAnalysis != null && taskAnalysis.isNeedsClarification()
                        && taskAnalysis.hasSubIntents()) {
                    log.info("[Router] Multi-intent request contains executable subtasks; "
                                    + "continue collaboration and defer incomplete state-changing operations: missingSlots={}",
                            taskAnalysis.getMissingSlots());
                }

                // ⭐ Step 3.6: 意图引导的查询改写
                // 根据意图类型选择改写策略：多跳→分解、模糊→扩展、精确→保留
                if (taskAnalysis != null && taskAnalysis.isMeaningful()) {
                    IntentGuidedQueryRewriter.RewriteResult rewriteResult =
                            queryRewriter.rewrite(enhancedQuestion, taskAnalysis);
                    if (!rewriteResult.rewrittenQuery().equals(enhancedQuestion)) {
                        log.info("[Router] 查询改写: '{}' → '{}' (策略={})",
                                enhancedQuestion, rewriteResult.rewrittenQuery(),
                                rewriteResult.rewriteStrategy());
                        enhancedQuestion = rewriteResult.rewrittenQuery();
                        // 将改写结果存入 Redis
                        try {
                            Map<String, Object> rewriteData = new LinkedHashMap<>();
                            rewriteData.put("original", question);
                            rewriteData.put("rewritten", rewriteResult.rewrittenQuery());
                            rewriteData.put("strategy", rewriteResult.rewriteStrategy());
                            rewriteData.put("subQueries", rewriteResult.subQueries());
                            redisTemplate.opsForValue().set(
                                    "a2a:rewrite:" + request.getRequestId(),
                                    objectMapper.writeValueAsString(rewriteData),
                                    java.time.Duration.ofSeconds(30));
                        } catch (Exception e) {
                            log.warn("[Router] 缓存改写结果失败: {}", e.getMessage());
                        }
                    }
                }

                // ⭐ 多 Agent 协作（所有提问均走规划→执行→合并）
                // 简单问题 plan() 返回单个子任务，merge() 直接返回
                // 复杂问题自动分解为多个子任务并行执行
                executionQuestion = addConversationContextIfNeeded(
                        enhancedQuestion, conversationHistory);
                log.info("[Router] 🤝 启动多 Agent 协作: question={}",
                        QuestionExtractor.truncate(executionQuestion, 120));
            result = executeCollaborative(
                    executionQuestion, userId, request.getRequestId(), request.getSessionId(),
                    emotion, taskAnalysis);
            // ⭐ 生成意图标签（用于用户画像统计），设置到 result
            if (result.getIntentTag() == null || result.getIntentTag().isBlank()) {
                result.setIntentTag(intentTag != null ? intentTag : intentTagGenerator.generate(question));
            }

            // ⭐⭐ 反思器 + 缓存写入 + 经验提取（公共后处理）
                return finalizeRouting(result, request, executionQuestion, emotion);

        } catch (Exception e) {
            log.error("[Router] 路由失败: {}", e.getMessage(), e);

            // ⭐ P1 预算清理
            if (budgetTracker != null) budgetTracker.endSession();

            // ⭐ P1 执行事件：记录失败
            if (executionTraceStore != null) {
                executionTraceStore.publishEvent(
                        request.getRequestId(), "unknown",
                        AgentExecutionState.State.RUNNING, AgentExecutionState.State.FAILED,
                        AgentExecutionState.EventType.TIMEOUT_REACHED,
                        "路由异常: " + e.getMessage(), 0, 0
                );
            }

            String errorMsg = ErrorRecoveryService.DEFAULT.resolveUserMessage(
                    AgentErrorCode.SYSTEM_ROUTE_FAILED, e.getMessage());
            return RoutingResult.builder()
                    .result(errorMsg)
                    .executionMode(RoutingResult.ExecutionMode.FALLBACK)
                    .workflowStatus(RoutingResult.WorkflowStatus.FAILED)
                .build();
        } finally {
            // ⭐ G4 运营指标：端到端路由延迟（覆盖全部路径：HEAVY/配额/经验/正常）
            long routeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - routeStart);
            opsMetrics.recordRouteLatency("router", "n/a", routeMs);
        }
    }

    /**
     * ⭐ 路由后处理公共方法：
     * 反思器评估 → 语义缓存写入 → 经验提取 → 完整决策写入 Redis
     * <p>
     * 经验匹配命中的路径和正常语义缓存的路径都汇聚到此，避免重复代码。
     */
    /**
     * ⭐ P4-A 情绪干预：将情绪等级、干预标志与安抚话术附加到路由结果。
     * 未触发情绪风险（NONE）或结果为 null 时原样返回。
     */
    private RoutingResult applyEmotion(RoutingResult r, EmotionCheckResult e) {
        return routeFinalizer.applyEmotion(r, e);
    }

    private RoutingResult finalizeRouting(RoutingResult result, RouteRequest request, String rawQuestion,
                                          EmotionCheckResult emotion) {
        return routeFinalizer.finalizeRouting(result, request, rawQuestion, emotion);
    }

    /**
     * 将任务分析结果追加到完整决策 JSON 中，供 Consumer 读取。
     * <p>
     * 读取 a2a:task-analysis:{requestId} 键，如果存在则将其内容
     * 作为 taskAnalysis 字段写入 full-decision 键。
     * </p>
     */
    private void appendTaskAnalysisToFullDecision(String requestId) {
        // delegated to RouteFinalizer.finalizeRouting()
    }

    /**
     * ⭐ 将任务分析结果存入 Redis（独立 key，下游 Agent 可读取）
     * <p>
     * Key: a2a:task-analysis:{requestId}<br>
     * TTL: 120 秒（与 full-decision 一致）
     * </p>
     */
    private void storeTaskAnalysisToRedis(String requestId, TaskAnalysisResult analysis) {
        routeFinalizer.storeTaskAnalysisToRedis(requestId, analysis);
    }

    // ==================== 多 Agent 协作 ====================

    /**
     * 多 Agent 协作：图分解 → 图执行 → 结果合并。
     * <p>
     * 处理跨领域复杂问题，如"推荐北京景点和川菜馆"，
     * 使用 DeepSeek 结构化分析生成带依赖关系的 DAG，交由
     * {@link RouteExecutionService} 通过统一的 LangGraph4j 入口执行。
     * </p>
     */
    private RoutingResult executeCollaborative(String question, Long userId, String requestId,
                                                EmotionCheckResult emotion) {
        return routeExecutionService.executeCollaborative(question, userId, requestId, emotion);
    }

    private RoutingResult executeCollaborative(String question, Long userId, String requestId,
                                                String sessionId,
                                                EmotionCheckResult emotion,
                                                TaskAnalysisResult taskAnalysis) {
        return routeExecutionService.executeCollaborative(
                question, userId, requestId, emotion, taskAnalysis, sessionId);
    }

    private Map<String, Object> buildContext(RouteRequest request) {
        return routeContextHelper.buildContext(request);
    }

    /** 在 API 层统一完成后记录对话，覆盖快车道、缓存和协作等所有返回路径。 */
    public void recordConversation(RouteRequest request, RoutingResult result) {
        if (request == null || result == null) return;
        routeContextHelper.appendConversation(
                request.getSessionId(), request.getQuestion(), result.getResult());
    }

    /** 为“如果、它、继续”等上下文依赖型追问补充最近一轮用户问题。 */
    static String addConversationContextIfNeeded(String question, List<String> history) {
        if (question == null || history == null || history.isEmpty()) return question;
        boolean contextDependent = question.matches(".*(如果|它|这个|那个|继续|还有|上面|前面|更看重|优先关注).*" );
        if (!contextDependent) return question;
        String lastUserQuestion = QuestionExtractor.extractLastUserQuestion(history);
        if (lastUserQuestion == null || lastUserQuestion.isBlank()) return question;
        return question + "\n\n[对话上下文]\n上一轮用户问题：" + lastUserQuestion
                + "\n请延续上一轮讨论的对象回答当前问题，不要再次要求用户说明产品类型。";
    }

    /**
     * A clarification may stop a single, currently unexecutable intent. For a multi-intent
     * request it must not suppress independent work that can already be completed (for
     * example, query popular products before asking which one should be ordered).
     */
    private static String declaredClarificationAgent(TaskAnalysisResult analysis) {
        if (analysis != null && analysis.getSubIntents() != null) {
            for (Map<String, Object> subIntent : analysis.getSubIntents()) {
                String agent = Objects.toString(subIntent.get("target_agent"), "")
                        .trim().toLowerCase(Locale.ROOT);
                if (Set.of("product", "order", "general").contains(agent)) return agent;
            }
        }
        return switch (Objects.toString(
                analysis != null ? analysis.getIntentCategory() : null, "")
                .trim().toUpperCase(Locale.ROOT)) {
            case "PRODUCT" -> "product";
            case "ORDER" -> "order";
            default -> "general";
        };
    }

    static boolean shouldShortCircuitForClarification(TaskAnalysisResult analysis) {
        return analysis != null
                && analysis.isNeedsClarification()
                && !analysis.hasSubIntents()
                && analysis.getClarificationQuestions() != null
                && !analysis.getClarificationQuestions().isEmpty();
    }

    private void loadConversationHistoryFromRedis(Map<String, Object> context, String sessionId) {
        // delegated to RouteContextHelper.buildContext()
    }
}
