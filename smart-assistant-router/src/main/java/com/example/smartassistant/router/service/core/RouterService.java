/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.agent.AgentEventBus;
import com.example.smartassistant.common.agent.AgentExecutionState;
import com.example.smartassistant.common.agent.FeedbackLog;
import com.example.smartassistant.common.agent.GoalContinuityArbiter;
import com.example.smartassistant.common.budget.BudgetTracker;
import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.model.tier.TieredModelRouter;
import com.example.smartassistant.common.model.tier.TierSelection;
import com.example.smartassistant.router.service.context.IntentDriftDetector;
import com.example.smartassistant.router.service.fusion.IntentFusionResult;
import com.example.smartassistant.router.service.fusion.IntentFusionService;
import com.example.smartassistant.common.error.ErrorRecoveryService;
import com.example.smartassistant.router.model.*;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.cache.SemanticRouteCacheService;
import com.example.smartassistant.router.service.evaluation.BadCaseMinerService;
import com.example.smartassistant.router.service.monitoring.NewMetricsCollector;
import com.example.smartassistant.router.service.evaluation.IntentGuidedQueryRewriter;
import com.example.smartassistant.router.service.experience.ExperienceService;
import com.example.smartassistant.router.service.quality.QualityEvaluationService;
import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.router.service.guardrail.GuardrailService;
import com.example.smartassistant.router.service.rag.RouterRagService;
import com.example.smartassistant.router.service.routing.KeywordFastRouteService;
import com.example.smartassistant.router.service.taskanalysis.TaskAnalysisService;
import com.example.smartassistant.router.service.tool.RoutingToolChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Router Service - 核心路由服务（单意图路由）
 *
 * <p>职责范围：</p>
 * <ul>
 *     <li>单意图路由与 Agent 调用</li>
 *     <li>降级方案：关键词匹配</li>
 * </ul>
 *
 * <p>已移除职责（迁移到 Consumer）：</p>
 * <ul>
 *     <li>日常建议生成 → Consumer.LLMSuggestionService</li>
 * </ul>
 * <p>⭐ v2: 多意图由 ExperienceService 的 BGE 向量匹配提供，Router 通过 secondaryIntents
 * 感知副意图并记录日志；未来可在协作执行场景下并行调用所有匹配的 Agent。</p>
 */
@Service
public class RouterService {
    
    private static final Logger log = LoggerFactory.getLogger(RouterService.class);
    
    private final AgentCallerService agentCallerService;
    private final StringRedisTemplate redisTemplate;
    private final RouterRagService ragService;
    private final SemanticRouteCacheService semanticCache;
    private final TaskPlannerService taskPlanner;
    private final ResultMerger resultMerger;
    private final ReflectionService reflectionService;
    private final ExperienceService experienceService;

    // ⭐ 基于图的意图执行引擎
    private final GraphExecutionService graphExecutionService;

    // ⭐ 任务分析服务（结构化提取实体/约束/风险/工具评分）
    private final TaskAnalysisService taskAnalysisService;

    // ⭐ LLM-as-Judge 质量评估服务（深层语义质检）
    private final QualityEvaluationService qualityEvaluationService;

    // ⭐ 意图引导的查询改写服务
    private final IntentGuidedQueryRewriter queryRewriter;

    // ⭐ P1 Bad Case 自动挖掘服务
    private final BadCaseMinerService badCaseMinerService;

    // ⭐ 轻量模型（用于兜底回复等简单推理）
    private final ChatClient lightChatClient;

    // ⭐ G3 统一模型接入层（按复杂度选档 + 平滑降级；无 Ollama 环境为 null）
    @Autowired(required = false)
    private TieredModelRouter tieredModelRouter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ⭐ 并行 Agent 执行线程池（用于独立子任务的真正并行执行）

    // ⭐ 子任务单 Agent 超时（毫秒）

    @Value("${router.agent.rag.enabled:false}")
    private boolean ragEnabled;

    @Value("${router.context.history.max-messages:10}")
    private int maxHistoryMessages;

    // ⭐ 多套兜底说辞轮换
    private static final List<String> FALLBACK_MESSAGES = List.of(
            "😅 抱歉让你等了这么久，目前服务似乎遇到了一些临时问题。请稍后再试一下，或者联系技术支持看看。谢谢你的耐心！",
            "🙏 不好意思让你久等了，系统这会儿有点忙不过来，暂时没办法回应你的问题。过一会儿再找我试试吧！",
            "🤗 哎呀，好像出了点小岔子……你先别着急，我这边正在努力恢复中，等一小会儿再来找我聊聊好吗？",
            "😊 真抱歉，刚才没能帮上忙。系统可能在打盹儿，你先去喝杯水，待会儿再来找我试试看？"
    );
    private final AtomicInteger fallbackIndex = new AtomicInteger(0);

    // ⭐ P1 Agent 执行事件总线（可选：无 Redis 时不记录事件）
    @Autowired(required = false)
    private AgentEventBus agentEventBus;

    // ⭐ L3 三路并行意图融合引擎（可选：降级走原 LLM 路径）
    @Autowired(required = false)
    private IntentFusionService intentFusionService;

    // ⭐ L5 意图漂移检测
    @Autowired(required = false)
    private IntentDriftDetector intentDriftDetector;

    // ⭐ P1 预算追踪
    @Autowired(required = false)
    private BudgetTracker budgetTracker;
    // ⭐ P2 新指标采集
    @Autowired(required = false)
    private NewMetricsCollector newMetrics;

    // ⭐ 关键词快车道服务（P2 改进：高频明确意图跳过 LLM 分诊）
    private final KeywordFastRouteService keywordFastRouteService;
    // ⭐ 路由级工具健康检查
    private final RoutingToolChecker routingToolChecker;

    // ⭐ 异常率降级服务（解决反常识2：异常处理本身制造异常的负反馈循环）
    private final DegradationService degradationService;

    // ⭐ P1 确定性护栏服务
    private final GuardrailService guardrailService;

    // ⭐ P2 Prompt 管理器
    private final PromptManager promptManager;

    public RouterService(AgentCallerService agentCallerService,
                         @Autowired(required = false) StringRedisTemplate redisTemplate,
                         RouterRagService ragService,
                         SemanticRouteCacheService semanticCache,
                         TaskPlannerService taskPlanner,
                         ResultMerger resultMerger,
                         ReflectionService reflectionService,
                         ExperienceService experienceService,
                         GraphExecutionService graphExecutionService,
                         TaskAnalysisService taskAnalysisService,
                         QualityEvaluationService qualityEvaluationService,
                         IntentGuidedQueryRewriter queryRewriter,
                        KeywordFastRouteService keywordFastRouteService,
                        @Autowired(required = false) RoutingToolChecker routingToolChecker,
                        @Autowired(required = false) DegradationService degradationService,
                        GuardrailService guardrailService,
                        PromptManager promptManager,
                         @Qualifier("lightChatModel") ChatModel lightModel,
                         @Autowired(required = false) BadCaseMinerService badCaseMinerService) {
        this.agentCallerService = agentCallerService;
        this.redisTemplate = redisTemplate;
        this.ragService = ragService;
        this.semanticCache = semanticCache;
        this.taskPlanner = taskPlanner;
        this.resultMerger = resultMerger;
        this.reflectionService = reflectionService;
        this.experienceService = experienceService;
        this.graphExecutionService = graphExecutionService;
        this.taskAnalysisService = taskAnalysisService;
        this.qualityEvaluationService = qualityEvaluationService;
        this.queryRewriter = queryRewriter;
        this.keywordFastRouteService = keywordFastRouteService;
        this.routingToolChecker = routingToolChecker;
        this.degradationService = degradationService;
        this.guardrailService = guardrailService;
        this.promptManager = promptManager;
        this.lightChatClient = ChatClient.create(lightModel);
        this.badCaseMinerService = badCaseMinerService;
    }

    /**
     * 执行路由决策并调用目标 Agent（统一入口）
     * 只识别并处理第一个意图
     */
    public RoutingResult route(RouteRequest request) {
        log.info("[Router] 收到路由请求: userId={}, sessionId={}, question={}",
                request.getUserId(), request.getSessionId(), truncate(request.getQuestion(), 100));

        // ⭐ P1 预算追踪：会话开始
        if (budgetTracker != null) {
            budgetTracker.startSession();
        }

        try {
            // Step 0: 经验匹配（优先级最高，在语义缓存之上）
            // ⭐ 经验匹配可直接跳过 LLM 推理，命中 TOOL 经验时甚至直接执行工具
            String question = request.getQuestion();

            // ⭐ P1 确定性护栏：检查高风险关键词（退款/退货/投诉等）
            GuardrailService.GuardrailCheckResult guardrail = guardrailService.check(question);
            boolean guardrailSkipped = guardrail.triggered() && guardrail.skipShortCircuit();
            boolean guardrailForceRag = guardrail.triggered() && guardrail.forceRag();

            if (guardrail.triggered()) {
                log.warn("[Router] 🛡️ 护栏激活: question='{}', matchedTerms={}, skipShortCircuit={}, forceRag={}",
                        truncate(question, 50), guardrail.matchedTerms(),
                        guardrail.skipShortCircuit(), guardrail.forceRag());
            }

            // ⭐ P2 Token 配额检查：开始路由前先检查用户级日配额
            if (budgetTracker != null && request.getUserId() != null) {
                String quotaMsg = budgetTracker.checkUserQuota(request.getUserId());
                if (quotaMsg != null) {
                    log.warn("[Router] ⚠️ 用户配额超限: userId={}, msg={}", request.getUserId(), quotaMsg);
                    return RoutingResult.builder()
                            .result(quotaMsg)
                            .agentName("builtin_fallback")
                            .confidence(0.2)
                            .build();
                }
            }

            // Step 0: 经验匹配（护栏触发 + skipShortCircuit 跳过短路）
            if (!guardrailSkipped) {
                ExperienceService.ExperienceMatchResult experienceMatch = experienceService.match(question);
            if (experienceMatch != null) {
                log.info("[Router] 🧠 经验匹配命中: type={}, agent={}, score={}",
                        experienceMatch.experience.getType(), experienceMatch.agentName,
                        String.format("%.2f", experienceMatch.matchScore));

                // ⭐ 多意图：记录副匹配日志（未来可用于并行调用）
                if (experienceMatch.secondaryIntents != null && !experienceMatch.secondaryIntents.isEmpty()) {
                    for (var si : experienceMatch.secondaryIntents) {
                        log.info("[Router] 🔀 副意图: agent={}, intent={}, score={}",
                                si.agentName, si.intentTag, String.format("%.2f", si.score));
                    }
                }

                // ⭐⭐ 多意图：Step 0 直接并行调度多个 Agent，不走 LLM 全管道
                //    经验匹配的 secondaryIntents 已明确哪些 Agent 需要参与
                boolean skipExperienceShortCircuit = experienceMatch.secondaryIntents != null
                        && !experienceMatch.secondaryIntents.isEmpty();

                if (skipExperienceShortCircuit) {
                    log.info("[Router] 🔀 多意图经验命中，Step 0 直接并行调度: "
                                    + "primary={}({}), secondaryCount={}",
                            experienceMatch.agentName, experienceMatch.experience.getIntentTag(),
                            experienceMatch.secondaryIntents.size());

                    // Step 0a: 收集所有需要调用的 Agent 任务
                    List<AgentTask> multiAgentTasks = new java.util.ArrayList<>();

                    // 主意图 → 构建任务
                    multiAgentTasks.add(buildAgentTask(
                            experienceMatch.agentName,
                            experienceMatch.reroutedQuestion != null
                                    ? experienceMatch.reroutedQuestion : question,
                            experienceMatch.experience.getIntentTag(),
                            experienceMatch.matchScore,
                            request));

                    // 副意图 → 各构建独立任务
                    for (var si : experienceMatch.secondaryIntents) {
                        multiAgentTasks.add(buildAgentTask(
                                si.agentName,
                                question, // 各 Agent 拿同一问题自行解析
                                si.intentTag,
                                si.score,
                                request));
                    }

                    // Step 0b: 并行调用所有 Agent（JDK 21 虚拟线程）
                    //   创建共享虚拟线程执行器，所有 Agent 调用各占一个虚拟线程
                    var virtExec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                    try {
                        String[] agentResults = new String[multiAgentTasks.size()];
                        @SuppressWarnings("unchecked")
                        java.util.concurrent.CompletableFuture<Void>[] futures =
                                new java.util.concurrent.CompletableFuture[multiAgentTasks.size()];

                        for (int i = 0; i < multiAgentTasks.size(); i++) {
                            final int idx = i;
                            final AgentTask task = multiAgentTasks.get(idx);
                            futures[idx] = java.util.concurrent.CompletableFuture
                                    .supplyAsync(() -> agentCallerService.callAgent(
                                            task.getAgentName(), task.getQuestion(),
                                            request.getUserId(), request.getRequestId()),
                                            virtExec) // 共享同一个虚拟线程执行器
                                    .thenAccept(r -> agentResults[idx] = r);
                        }

                        // Step 0c: 等待全部完成
                        java.util.concurrent.CompletableFuture.allOf(futures).join();

                        // Step 0d: 合并结果
                        StringBuilder merged = new StringBuilder();
                        String primaryResult = null;
                        String primaryAgent = multiAgentTasks.get(0).getAgentName();
                        String primaryIntent = multiAgentTasks.get(0).getIntentTag();
                        double primaryConfidence = multiAgentTasks.get(0).getConfidence();

                        for (int i = 0; i < agentResults.length; i++) {
                            if (agentResults[i] != null && !agentResults[i].isBlank()) {
                                if (i == 0) {
                                    primaryResult = agentResults[i];
                                } else {
                                    merged.append("\n").append(agentResults[i]);
                                }
                            }
                        }

                        String finalReply = primaryResult != null
                                ? primaryResult + merged
                                : merged.toString();
                        if (finalReply.isBlank()) {
                            finalReply = "抱歉，暂时无法处理您的问题，请稍后再试。";
                        }

                        RoutingResult result = RoutingResult.builder()
                                .result(finalReply)
                                .agentName(primaryAgent)
                                .confidence(primaryConfidence)
                                .intentTag(primaryIntent)
                                .build();
                        return finalizeRouting(result, request, question);

                    } finally {
                        virtExec.shutdown();
                    }
                }

                // ⭐ 单意图：TOOL / COMMON / REACT 经验直接短路
                if (experienceMatch.isToolExperience) {
                        RoutingResult result = callAgentAndFinalize(
                                experienceMatch.agentName,
                                experienceMatch.reroutedQuestion,
                                experienceMatch.matchScore,
                                experienceMatch.experience.getIntentTag(),
                                request, question);
                        if (result != null) return result;
                    }

                    // COMMON / REACT 经验：设置路由目标，跳过 TaskPlanner
                    if (experienceMatch.skipTaskPlanning) {
                        String agentQuestion = experienceMatch.reroutedQuestion != null
                                ? experienceMatch.reroutedQuestion : question;
                        RoutingResult result = callAgentAndFinalize(
                                experienceMatch.agentName, agentQuestion,
                                experienceMatch.matchScore,
                                experienceMatch.experience.getIntentTag(),
                                request, question);
                        if (result != null) return result;
                    }
                } // end experienceMatch != null
            } // end !guardrailSkipped (护栏激活时跳过经验匹配短路)

            // Step 0.5: 关键词快车道（护栏触发 + skipShortCircuit 时跳过）
            // 优先级：经验匹配 > 关键词快车道 > 语义缓存 > LLM 意图识别
            KeywordFastRouteService.MatchResult keywordMatch = !guardrailSkipped
                    ? keywordFastRouteService.match(question) : null;
            if (keywordMatch != null) {
                log.info("[Router] ⚡ 关键词快车道命中: rule={}, agent={}, intent={}, confidence={}",
                        keywordMatch.getMatchedRuleName(), keywordMatch.getTargetAgent(),
                        keywordMatch.getIntentTag(), keywordMatch.getConfidence());
                RoutingResult result = callAgentAndFinalize(
                        keywordMatch.getTargetAgent(), question,
                        keywordMatch.getConfidence(), keywordMatch.getIntentTag(),
                        request, question);
                if (result != null) return result;
            }

            // Step 1: 检查语义缓存（仅存 agent 名，不存回复内容）
            SemanticRouteCacheService.CachedRouteDecision cached = semanticCache.getCachedDecision(question);
            
            // Step 2: 构建上下文
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

            // Step 4: 单意图路由（语义缓存命中时直接返回缓存回复）
            RoutingResult result;
            if (cached != null) {
                log.info("[Router] ⚡ 语义缓存命中: intent={}, agent={}, hit={}", cached.intentTag, cached.agentName, cached.hitCount);
                if (cached.reply != null && !cached.reply.isBlank()) {
                    // ⭐ 有缓存回复，直接返回（加变化前缀避免重复）
                    String wrapped = semanticCache.wrapCachedReply(cached.reply, cached, request.getQuestion(), request.getUserId());
                    result = RoutingResult.builder()
                            .result(wrapped)
                            .agentName(cached.agentName)
                            .confidence(cached.confidence)
                            .intentTag(cached.intentTag)  // ⭐ 从缓存中恢复 intentTag
                            .fromCache(true)  // ⭐ 标记为缓存命中，Consumer 据此跳过文档沉淀
                            .build();
                } else {
                    // 仅有路由决策无回复内容，仍需调用 Agent
                    result = RoutingResult.builder()
                            .result("")
                            .agentName(cached.agentName)
                            .confidence(cached.confidence)
                            .intentTag(cached.intentTag)  // ⭐ 从缓存中恢复 intentTag
                            .build();
                    // ⭐ 缓存了 builtin_fallback 但无回复 → 不使用缓存决策，走内联兜底
                    if ("builtin_fallback".equals(cached.agentName) || "none".equals(cached.agentName)) {
                        log.warn("[Router] 缓存命中但 agent={} 无可用 Agent，降级到内联兜底", cached.agentName);
                        return inlineFallback(enhancedQuestion);
                    }
                    if (cached.agentName != null) {
                        String agentReply = agentCallerService.callAgent(cached.agentName, enhancedQuestion, userId, request.getRequestId());
                        if (agentReply != null) {
                            result.setResult(agentReply);
                        }
                    }
                }
            } else {
                // ⭐ Step 3.5: L3 三路并行意图融合 + 任务分析
                // 规则+小模型+LLM 三路融合，命中高置信路径则跳过 LLM
                @SuppressWarnings("unchecked")
                List<String> conversationHistory =
                        (List<String>) context.get("conversationHistory");

                // L3 融合：规则/小模型/LLM 并行
                IntentFusionResult fusionResult = null;
                if (intentFusionService != null) {
                    fusionResult = intentFusionService.fuse(
                            enhancedQuestion,
                            conversationHistory != null ? conversationHistory : Collections.emptyList()
                    );
                }

                TaskAnalysisResult taskAnalysis = null;
                String intentTag = null;
                double confidence = 0.7;

                if (fusionResult != null && fusionResult.isValid()
                        && !"LLM".equals(fusionResult.source())) {
                    // 规则或小模型命中 → 跳过 LLM，使用融合结果
                    intentTag = fusionResult.intentTag();
                    confidence = fusionResult.confidence();
                    log.info("[Router] ⚡ L3 融合命中: source={}, intent={}, conf={}, elapsed={}ms",
                            fusionResult.source(), intentTag, confidence, fusionResult.elapsedMs());

                    // 构造一个空的 TaskAnalysisResult（只设 intentTag，下游 Agent 可用）
                    taskAnalysis = new TaskAnalysisResult();
                    taskAnalysis.setIntentCategory(intentTag);
                    taskAnalysis.setConfidence(confidence);

                } else {
                    // LLM 路径或融合降级
                    if (fusionResult != null) {
                        log.info("[Router] 🤖 L3 融合走 LLM 路径: source={}, elapsed={}ms",
                                fusionResult.source(), fusionResult.elapsedMs());
                    }
                    taskAnalysis = taskAnalysisService.analyze(
                            enhancedQuestion,
                            conversationHistory != null ? conversationHistory : Collections.emptyList()
                    );
                    if (taskAnalysis != null && taskAnalysis.isMeaningful()) {
                        intentTag = taskAnalysis.getIntentCategory();
                    }
                }

                // ⭐ L5 意图漂移检测：多轮对话中检测用户意图是否漂移
                if (intentDriftDetector != null && intentTag != null
                        && conversationHistory != null && !conversationHistory.isEmpty()) {
                    var drift = intentDriftDetector.detect(enhancedQuestion, conversationHistory);
                    if (drift.driftDetected()) {
                        log.warn("[Router] 🔄 意图漂移: from='{}' to='{}', similarity={}, strong={}",
                                truncate(drift.previousQuestion(), 30),
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
                    String lastUserQuestion = extractLastUserQuestion(conversationHistory);
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

                // ⭐ Step 3.6: 意图引导的查询改写
                // 根据意图类型选择改写策略：多跳→分解、模糊→扩展、精确→保留
                if (taskAnalysis.isMeaningful()) {
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
                        } catch (Exception ignored) {}
                    }
                }

                // ⭐ 多 Agent 协作（所有提问均走规划→执行→合并）
                // 简单问题 plan() 返回单个子任务，merge() 直接返回
                // 复杂问题自动分解为多个子任务并行执行
                log.info("[Router] 🤝 启动多 Agent 协作: question={}", truncate(enhancedQuestion, 80));
                result = executeCollaborative(enhancedQuestion, userId, request.getRequestId());
            }

            // ⭐ 生成意图标签（用于用户画像统计），设置到 result
            String intentTag = semanticCache.generateIntentTag(question);
            result.setIntentTag(intentTag);

            // ⭐⭐ 反思器 + 缓存写入 + 经验提取（公共后处理）
            return finalizeRouting(result, request, extractRawQuestion(question));

        } catch (Exception e) {
            log.error("[Router] 路由失败: {}", e.getMessage(), e);

            // ⭐ P1 预算清理
            if (budgetTracker != null) budgetTracker.endSession();

            // ⭐ P1 执行事件：记录失败
            if (agentEventBus != null) {
                agentEventBus.publishEvent(
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
                .build();
        }
    }

    /**
     * ⭐ 路由后处理公共方法：
     * 反思器评估 → 语义缓存写入 → 经验提取 → 完整决策写入 Redis
     * <p>
     * 经验匹配命中的路径和正常语义缓存的路径都汇聚到此，避免重复代码。
     */
    private RoutingResult finalizeRouting(RoutingResult result, RouteRequest request, String rawQuestion) {
        String question = request.getQuestion();
        Long userId = request.getUserId();
        String intentTag = result.getIntentTag();
        if (intentTag == null || intentTag.isBlank()) {
            intentTag = semanticCache.generateIntentTag(question);
            result.setIntentTag(intentTag);
        }

        // ⭐ P1 工具健康检查：路由到 Agent 前检查关键工具是否就绪（routingToolChecker 可为 null）
        if (routingToolChecker != null && result.getAgentName() != null) {
            var health = routingToolChecker.checkAgentHealth(result.getAgentName());
            if (!health.isHealthy()) {
                log.warn("[Router] ⚠️ 路由到 Agent={} 但工具不健康: {}",
                        result.getAgentName(), health.getMessage());
            }
        }

        // ⭐⭐ 反思器：对非缓存的新结果进行质量评估（纯规则评分，不调 LLM）
        double reflectScore = 0.7; // 默认分（不触发 LLM 质检）
        if (result.getResult() != null && !result.getResult().isBlank()
                && result.getAgentName() != null && !"none".equals(result.getAgentName())
                && !Boolean.TRUE.equals(result.getFromCache())) {
            ReflectionResult reflection = reflectionService.evaluate(
                    question, result.getResult(), result.getAgentName(), intentTag, userId);
            reflectScore = reflection.getScore();
            if (!reflection.isAcceptable()) {
                log.warn("[Router] 🪞 反思不通过: score={}, agent={}, reason={}",
                        String.format("%.2f", reflection.getScore()),
                        result.getAgentName(), reflection.getReason());
                // 最多重试 1 次，换 fallback Agent
                String retryResult = reflectionService.retry(
                        question, result.getResult(), result.getAgentName(),
                        intentTag, userId, request.getRequestId());
                if (retryResult != null && !retryResult.equals(result.getResult())) {
                    result.setResult(retryResult);
                    log.info("[Router] 🪞 反思重试成功，已替换低质量回复");
                }
            }
        }

        // ⭐⭐ LLM-as-Judge 质量评估（深层语义质检）
        // 仅在反思器评分处于边界区间(0.5~0.8)时触发，避免为明显好/差的回复增加开销
        boolean qualityPassed = true;
        if (result.getResult() != null && !result.getResult().isBlank()
                && result.getAgentName() != null && !"none".equals(result.getAgentName())
                && !Boolean.TRUE.equals(result.getFromCache())) {
            QualityEvaluationResult quality = qualityEvaluationService.evaluate(
                    question, result.getResult(), reflectScore);
            if (quality.isCompleted() && !quality.isPassing(0.6)) {
                qualityPassed = false;
                log.warn("[Router] 🔍 质量评估不通过: overall={}, hallucination={}, reason={}",
                        String.format("%.2f", quality.getOverall()),
                        String.format("%.2f", quality.getHallucination()),
                        quality.getReason());
            }
        }

        // ⭐ 缓存路由决策 + 回复
        String agentName = result.getAgentName();
        String requestId = request.getRequestId();
        String reply = result.getResult();

        if (agentName != null && !"none".equals(agentName) && !agentName.isBlank()) {
            // 缓存路由决策（含审计日志 + 精确匹配覆盖）
            semanticCache.saveDecision(requestId, question, agentName, result.getConfidence(), userId, intentTag, request.getSessionId());
            semanticCache.saveExactMatch(rawQuestion != null ? rawQuestion : question, intentTag);

            // 缓存回复内容（低质量回复不缓存，防污染）
            if (!Boolean.TRUE.equals(result.getFromCache()) && qualityPassed) {
                if (reply != null && !reply.isBlank() && !reply.startsWith("❌") && !reply.startsWith("⚠️")
                        && reply.length() >= 20) {
                    semanticCache.saveReply(question, reply, agentName, intentTag, Boolean.TRUE.equals(result.getAdminOperation()));
                }
            }

            // ⭐⭐ 经验提取（Agent 执行成功后才提取）
            if (!Boolean.TRUE.equals(result.getFromCache())) {
                // 提取 COMMON 经验
                experienceService.extractCommonExperience(question, agentName, intentTag);

                // 提取 TOOL 经验（如果回复中包含工具调用信息）
                extractToolExperienceIfApplicable(reply, agentName, intentTag, question);
            }
        }

        // ⭐ P1 Agent 执行事件：记录路由决策完成
        if (agentEventBus != null) {
            agentEventBus.publishEvent(
                    request.getRequestId(), result.getAgentName(),
                    AgentExecutionState.State.RUNNING,
                    result.getResult() != null ? AgentExecutionState.State.COMPLETED
                            : AgentExecutionState.State.FAILED,
                    AgentExecutionState.EventType.EXECUTION_COMPLETED,
                    "路由决策完成, agent=" + result.getAgentName()
                            + ", confidence=" + result.getConfidence() + ", intent=" + intentTag,
                    0, 0
            );
        }

        // ⭐ 将完整决策写入 Redis，供 Consumer 阻塞读取
        if (requestId != null && !requestId.isBlank() && agentName != null) {
            semanticCache.saveFullDecisionForConsumer(requestId, agentName,
                    result.getConfidence(), reply, intentTag);

            // ⭐ 如有任务分析结果，追加到完整决策中
            appendTaskAnalysisToFullDecision(requestId);
        }

        // P1 ⭐ Bad Case 自动挖掘：低置信度路由决策写入 Redis
        if (badCaseMinerService != null) {
            badCaseMinerService.record(new BadCaseMinerService.RoutingDecision(
                    request.getQuestion(),
                    result.getIntentTag(),
                    result.getConfidence(),
                    result.getAgentName(),
                    request.getSessionId(),
                    request.getUserId()
            ));
        }

        // ⭐ Agent 反馈模式监控：定期采样全局模式计数器（日志 + 后续可对接告警）
        var patternCounts = FeedbackLog.getPatternCountsSnapshot();
        if (!patternCounts.isEmpty()) {
            log.debug("[Router] Agent 反馈模式统计: {}", patternCounts);
        }

        // ⭐ P1 预算追踪：结束会话并检查超限
        if (budgetTracker != null) {
            var budgetStatus = budgetTracker.checkSession();
            if (budgetStatus.exceeded()) {
                log.warn("[Router] ⚠️ 会话预算超限: {}", budgetStatus.reason());
                if (newMetrics != null) newMetrics.recordBudgetExceeded();
            }
            budgetTracker.endSession();
        }

        return result;
    }

    /**
     * 将任务分析结果追加到完整决策 JSON 中，供 Consumer 读取。
     * <p>
     * 读取 a2a:task-analysis:{requestId} 键，如果存在则将其内容
     * 作为 taskAnalysis 字段写入 full-decision 键。
     * </p>
     */
    private void appendTaskAnalysisToFullDecision(String requestId) {
        if (redisTemplate == null || requestId == null || requestId.isBlank()) return;
        try {
            String analysisKey = "a2a:task-analysis:" + requestId;
            String analysisJson = redisTemplate.opsForValue().get(analysisKey);
            if (analysisJson == null || analysisJson.isBlank()) return;

            String decisionKey = "a2a:route:full-decision:" + requestId;
            String decisionJson = redisTemplate.opsForValue().get(decisionKey);
            if (decisionJson == null || decisionJson.isBlank()) return;

            @SuppressWarnings("unchecked")
            Map<String, Object> decision = objectMapper.readValue(decisionJson, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> analysis = objectMapper.readValue(analysisJson, Map.class);
            decision.put("taskAnalysis", analysis);
            redisTemplate.opsForValue().set(decisionKey,
                    objectMapper.writeValueAsString(decision), 120, TimeUnit.SECONDS);

            log.debug("[Router] 🔍 任务分析已追加到完整决策: requestId={}", requestId);
        } catch (Exception e) {
            log.debug("[Router] 追加任务分析到完整决策时异常: {}", e.getMessage());
        }
    }

    /**
     * ⭐ 将任务分析结果存入 Redis（独立 key，下游 Agent 可读取）
     * <p>
     * Key: a2a:task-analysis:{requestId}<br>
     * TTL: 120 秒（与 full-decision 一致）
     * </p>
     */
    private void storeTaskAnalysisToRedis(String requestId, TaskAnalysisResult analysis) {
        if (requestId == null || requestId.isBlank() || redisTemplate == null) return;
        try {
            String key = "a2a:task-analysis:" + requestId;
            String json = objectMapper.writeValueAsString(analysis);
            redisTemplate.opsForValue().set(key, json, 120, TimeUnit.SECONDS);
            log.info("[Router] 🔍 任务分析已存储: requestId={}, intent={}, entities={}",
                    requestId, analysis.getIntentCategory(), analysis.getEntities().size());
        } catch (Exception e) {
            log.warn("[Router] 存储任务分析失败: {}", e.getMessage());
        }
    }

    /**
     * 尝试从 Agent 回复中提取 TOOL 经验。
     * 当回复包含明显的工具调用模式时，提取为 TOOL 经验以备后续复用。
     */
    private void extractToolExperienceIfApplicable(String reply, String agentName, String intentTag, String question) {
        if (reply == null || reply.isBlank() || agentName == null || intentTag == null) return;

        // 检测工具调用模式：回复中包含特定的关键词表明使用了某个工具
        // Order Agent 工具模式
        switch (agentName) {
            case "order_agent" -> {
                if (reply.contains("订单") && (reply.contains("状态") || reply.contains("查询") || reply.contains("ORD-"))) {
                    String params = extractOrderParams(question);
                    experienceService.extractToolExperience(question, agentName, intentTag,
                            "queryOrder", params, "订单{orderId}当前状态为{status}");
                }
                if (reply.contains("退款") || reply.contains("退货")) {
                    experienceService.extractToolExperience(question, agentName, intentTag,
                            "refundOrder", "{\"orderId\": \"" + extractOrderId(question) + "\"}", "退款申请已提交");
                }
            }
            // Product Agent 工具模式
            case "product_agent" -> {
                if (reply.contains("价格") || reply.contains("多少钱") || reply.contains("报价")) {
                    experienceService.extractToolExperience(question, agentName, intentTag,
                            "queryPrice", "{\"product\": \"" + extractProductName(question) + "\"}", "{product}的价格为{price}");
                }
                if (reply.contains("库存") || reply.contains("有货") || reply.contains("缺货")) {
                    experienceService.extractToolExperience(question, agentName, intentTag,
                            "checkStock", "{\"product\": \"" + extractProductName(question) + "\"}", "{product}的库存状态为{status}");
                }
            }
            // General Agent 工具模式
            case "general_agent" -> {
                if (reply.contains("天气") || reply.contains("气温") || reply.contains("下雨")) {
                    experienceService.extractToolExperience(question, agentName, intentTag,
                            "getWeather", "{\"location\": \"" + extractLocation(question) + "\"}", "{location}当前天气为{weather}");
                }
                if (reply.contains("新闻") || reply.contains("热点") || reply.contains("头条")) {
                    experienceService.extractToolExperience(question, agentName, intentTag,
                            "getHotNews", "{}", "以下是近期热点新闻");
                }
            }
        }
    }

    // ==================== 多 Agent 协作 ====================

    /**
     * 多 Agent 协作：图分解 → 图执行 → 结果合并。
     * <p>
     * 处理跨领域复杂问题，如"推荐北京景点和川菜馆"，
     * 使用 {@link TaskPlannerService#planToGraph(String)} 分解为带依赖关系的 DAG，
     * 由 {@link GraphExecutionService#execute} 按拓扑顺序并行执行。
     * </p>
     */
    private RoutingResult executeCollaborative(String question, Long userId, String requestId) {
        long start = System.currentTimeMillis();

        // ⭐ Step 0: 检查是否有可用 Agent（无 Agent 时直接使用内联兜底）
        if (agentCallerService.getAvailableAgentCount() == 0) {
            log.warn("[Collaborative] 无可用 Agent，降级到内联 ChatClient 兜底");
            return inlineFallback(question);
        }

        // ⭐ Step 0.5: 降级检测 — 解决反常识 2（异常处理本身会制造异常）
        //   degradationService 可为 null（测试 / 未装配降级服务时默认 NORMAL 不降级）
        DegradationService.DegradationLevel degLevel = (degradationService != null)
                ? degradationService.getDegradationLevel()
                : DegradationService.DegradationLevel.NORMAL;
        // ⭐ 半开（HALF_OPEN）熔断探测：放行一次请求验证恢复
        if (degLevel == DegradationService.DegradationLevel.HALF_OPEN) {
            log.info("[Collaborative] 🟡 半开探测: 放行一次请求验证恢复");
            RoutingResult probeResult = inlineFallback(question);
            boolean success = probeResult != null && probeResult.getResult() != null
                    && !probeResult.getResult().isBlank()
                    && !probeResult.getResult().startsWith("❌");
            if (degradationService != null) {
                degradationService.recordProbeResult(success);
            }
            return probeResult;
        }
        if (degLevel == DegradationService.DegradationLevel.HEAVY) {
            log.warn("[Collaborative] 🔴 重度降级(错误率>40%)，跳过所有 Agent 调用，回退到内联兜底");
            return inlineFallback(question);
        }
        if (degLevel == DegradationService.DegradationLevel.LIGHT) {
            log.warn("[Collaborative] 🟡 轻度降级(错误率>20%)，跳过复杂 DAG，降级为单 Agent 通用回复");
            // 轻度降级：不走 DAG 分解，直接使用 General Agent 简化回复
            String reply = agentCallerService.callAgent("general_agent", question, userId, requestId);
            if (reply == null || reply.isBlank() || reply.startsWith("❌")) {
                return inlineFallback(question);
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("[Collaborative] 降级单 Agent 完成: elapsed={}ms, replyLen={}", elapsed, reply.length());
            return RoutingResult.builder().result(reply).agentName("general_agent").confidence(1.0).build();
        }

        // Step 1: 图分解（带依赖关系的 DAG）
        IntentGraph graph = taskPlanner.planToGraph(question);
        if (graph.getNodeCount() == 0) {
            log.warn("[Collaborative] 图分解为空，降级到内联 ChatClient 兜底");
            return inlineFallback(question);
        }
        log.info("[Collaborative] 图分解完成: {} 个节点, hasDeps={}, maxParallelism={}",
                graph.getNodeCount(), graph.hasDependency(), graph.getMaxParallelism());

        String eventsKey = requestId != null ? SSE_EVENTS_KEY_PREFIX + requestId : null;

        // Step 2: 图执行（根据拓扑并行执行，支持 Checkpoint/条件边/Handoff）
        List<SubTaskResult> results = graphExecutionService.executeWithResume(graph, userId, eventsKey, requestId);

        // Step 3: SSE — 汇总中
        storeSseEvent(eventsKey, "summarizing", "正在整合多源信息...", null);

        // Step 4: 合并结果
        String merged = resultMerger.merge(question, results);

        long elapsed = System.currentTimeMillis() - start;

        // Step 5: 终极兜底 — 所有子任务均失败或结果为空时走内联 ChatClient
        boolean allFailed = results.isEmpty() || results.stream().noneMatch(SubTaskResult::isSuccess);
        if (allFailed || merged == null || merged.isBlank()) {
            log.warn("[Collaborative] 所有子任务均失败，降级到内联 ChatClient 兜底");
            return inlineFallback(question);
        }

        log.info("[Collaborative] 协作完成: {} 个子任务, 耗时={}ms, 结果长度={}",
                results.size(), elapsed, merged.length());

        String firstAgent = results.stream()
                .map(SubTaskResult::getAgentName)
                .filter(Objects::nonNull)
                .findFirst().orElse("none");

        // ⭐ 提取 REACT 经验（多步协作链路）
        if (graph.getNodeCount() >= 2) {
            experienceService.extractReactExperience(question,
                    graph.getAllNodes().stream()
                            .map(n -> new SubTask(n.getId(), n.getDescription(), n.getTargetAgent(), n.getDependsOn()))
                            .collect(java.util.stream.Collectors.toList()));
        }

        return RoutingResult.builder()
                .result(merged)
                .agentName(firstAgent)
                .confidence(0.8)
                .build();
    }

    // ═══════════════════════════════════════════════════════════
    // ⭐ 共享：调用 Agent 并构建路由结果（消除 3 处重复）
    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    // ⭐ 共享：构建 Agent 任务（用于多意图并行调度）
    // ═══════════════════════════════════════════════════════════

    private static class AgentTask {
        final String agentName;
        final String question;
        final String intentTag;
        final double confidence;
        AgentTask(String agentName, String question, String intentTag, double confidence) {
            this.agentName = agentName;
            this.question = question;
            this.intentTag = intentTag;
            this.confidence = confidence;
        }
        String getAgentName() { return agentName; }
        String getQuestion() { return question; }
        String getIntentTag() { return intentTag; }
        double getConfidence() { return confidence; }
    }

    /** 从经验匹配结果构建一个 Agent 调用任务 */
    private AgentTask buildAgentTask(String agentName, String question,
                                      String intentTag, double confidence,
                                      RouteRequest request) {
        return new AgentTask(agentName, question, intentTag, confidence);
    }

    // ═══════════════════════════════════════════════════════════
    // ⭐ 共享：调用 Agent 并构建路由结果（消除 3 处重复）
    // ═══════════════════════════════════════════════════════════

    /** 调用 Agent → 构建 RoutingResult → finalizeRouting 后处理。空回复返回 null。 */
    private RoutingResult callAgentAndFinalize(String agentName, String agentQuestion,
                                                double confidence, String intentTag,
                                                RouteRequest request, String rawQuestion) {
        String agentReply = agentCallerService.callAgent(
                agentName, agentQuestion, request.getUserId(), request.getRequestId());
        if (agentReply == null || agentReply.isBlank()) {
            return null;
        }
        RoutingResult result = RoutingResult.builder()
                .result(agentReply)
                .agentName(agentName)
                .confidence(confidence)
                .intentTag(intentTag)
                .build();
        return finalizeRouting(result, request, rawQuestion);
    }

    /**
     * 内联 Ollama 兜底（handleSingleIntent 的第三、四层），
     * 当所有 Agent 调用失败时的终极回应方案。
     * <p>
     * 纯本地推理：
     * <ol>
     *   <li>本地 Ollama（deepseek-r1:7b）</li>
     *   <li>失败 → 预设文案（终极兜底）</li>
     * </ol>
     */
    private RoutingResult inlineFallback(String question) {
        // ⭐ G3：按复杂度选档 + 平滑降级调用
        // P2 Prompt 已外部化到 prompts/router/inline-fallback.txt
        String warmSystem = promptManager.inlineFallback();
        try {
            String localReply;
            if (tieredModelRouter != null) {
                Prompt prompt = new Prompt(List.of(
                        new SystemMessage(warmSystem),
                        new UserMessage(question)));
                TierSelection sel = tieredModelRouter.call(prompt, question, null);
                localReply = sel.response() != null && sel.response().getResult() != null
                        ? sel.response().getResult().getOutput().getText() : null;
            } else {
                localReply = lightChatClient.prompt()
                        .system(warmSystem)
                        .user(question)
                        .call()
                        .content();
            }
            if (localReply != null && !localReply.isBlank()) {
                return RoutingResult.builder()
                        .result(localReply)
                        .agentName("builtin_fallback")
                        .confidence(0.2)
                        .build();
            }
        } catch (Exception e) {
            log.warn("[Router] 本地推理兜底失败（含 G3 档位全失败）: {}", e.getMessage());
        }

        // 终极降级 — 预设文案轮换
        int idx = fallbackIndex.getAndUpdate(i -> (i + 1) % FALLBACK_MESSAGES.size());
        return RoutingResult.builder()
                .result(FALLBACK_MESSAGES.get(idx))
                .agentName("none")
                .confidence(0.0)
                .build();
    }

    private static final String SSE_EVENTS_KEY_PREFIX = "routing:sse:events:";
    private static final long SSE_EVENTS_TTL_SECONDS = 120;

    /**
     * ⭐ 存储 SSE 事件到 Redis（供 Consumer 读取并转发给前端）
     */
    private void storeSseEvent(String eventsKey, String type, String content, String agent) {
        if (eventsKey == null || redisTemplate == null) return;
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\"type\":\"").append(type).append("\"");
            if (content != null) {
                json.append(",\"content\":\"").append(escapeJson(content)).append("\"");
            }
            if (agent != null) {
                json.append(",\"agent\":\"").append(agent).append("\"");
            }
            json.append("}");
            redisTemplate.opsForList().rightPush(eventsKey, json.toString());
            redisTemplate.expire(eventsKey, SSE_EVENTS_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[Router] 存储 SSE 事件失败: {}", e.getMessage());
        }
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * 从对话历史中提取最近一条用户问题。
     * 历史格式：["用户：...", "助手：...", "用户：..."]
     */
    private String extractLastUserQuestion(List<String> history) {
        if (history == null || history.isEmpty()) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            String msg = history.get(i);
            if (msg.startsWith("用户：") || msg.startsWith("用户:")) {
                return msg.substring(3).trim();
            }
        }
        return null;
    }

    /*
      子任务结果
     */
    /**
     * 构建上下文
     * ⭐ 从 Redis 加载会话历史，提升多轮对话的路由准确性
     */
    private Map<String, Object> buildContext(RouteRequest request) {
        Map<String, Object> context = new HashMap<>();
        String sessionId = request.getSessionId();
        if (sessionId != null) {
            context.put("sessionId", sessionId);
        }
        if (request.getUserId() != null) {
            context.put("userId", request.getUserId());
        }

        // ⭐ 从 Redis 加载会话上下文（最近 N 条消息）
        loadConversationHistoryFromRedis(context, sessionId);

        return context;
    }

    /**
     * 从 Redis 加载会话历史消息
     */
    private void loadConversationHistoryFromRedis(Map<String, Object> context, String sessionId) {
        if (redisTemplate == null || sessionId == null || sessionId.isBlank()) {
            return;
        }

        try {
            String historyKey = "chat:history:" + sessionId;
            Long size = redisTemplate.opsForList().size(historyKey);

            if (size == null || size == 0) {
                log.debug("[Router] Redis 中无会话历史: sessionId={}", sessionId);
                return;
            }

            // 读取最近 N 条消息
            int start = 0;
            int end = Math.min(maxHistoryMessages - 1, size.intValue() - 1);
            List<String> history = redisTemplate.opsForList().range(historyKey, start, end);

            if (history != null && !history.isEmpty()) {
                context.put("conversationHistory", history);
                log.debug("[Router] 从 Redis 加载会话历史: sessionId={}, messages={}",
                        sessionId, history.size());
            }
        } catch (Exception e) {
            log.warn("[Router] 从 Redis 加载会话历史失败: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }
    
/**
    private static class ExtractedContext {
        String location;
        String time;
        
        ExtractedContext(String location, String time) {
            this.location = location;
            this.time = time;
        }
    }
    /**
     * ⭐ 从完整 Prompt 中提取原始问题
     * <p>
     * Router 接收到的 question 可能包含【用户历史信息】【用户画像】【当前问题】等模板标记。
     * 提取【当前问题】后的文本作为缓存使用的原始问题，确保同一问题的 MD5 一致。
     * <p>
     * 格式示例：
     * 【用户历史信息】
     * - 历史查询: 3次
     * 【当前问题】
     * 故宫几点关门
     * <p>
     * 提取后返回: "故宫几点关门"
     */
    private String extractRawQuestion(String question) {
        if (question == null || question.isBlank()) return question;
        // 查找【当前问题】标记
        int currentQuestionIdx = question.indexOf("【当前问题】");
        if (currentQuestionIdx >= 0) {
            String after = question.substring(currentQuestionIdx + "【当前问题】".length()).trim();
            // 去除可能的前缀（如换行、编号等），取纯文本
            return after.replaceAll("^[\\s\\n\\r]+", "").trim();
        }
        // 无标记直接返回完整文本
        return question.trim();
    }
    
    /**
     * ⭐ 截断字符串（用于日志）
     * 已提升为 public static，供外部服务使用
     */
    public static String truncate(String str, int maxLength) {
        if (str == null) return "";
        return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
    }

    // ==================== 参数提取工具（用于 TOOL 经验提取）====================

    /**
     * 从问题中提取订单 ID
     */
    private String extractOrderId(String question) {
        if (question == null) return "";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("ORD[-_]?\\d+");
        java.util.regex.Matcher matcher = pattern.matcher(question);
        return matcher.find() ? matcher.group() : question;
    }

    /**
     * 从问题中提取订单参数
     */
    private String extractOrderParams(String question) {
        String orderId = extractOrderId(question);
        if (!orderId.isEmpty() && !orderId.equals(question)) {
            return "{\"orderId\": \"" + orderId + "\"}";
        }
        return "{\"orderId\": \"" + question + "\"}";
    }

    /**
     * 从问题中提取商品名称
     */
    private String extractProductName(String question) {
        if (question == null) return "";
        // 常见商品关键词后提取
        String[] productIndicators = {"多少钱", "价格", "有货", "库存", "怎么样", "好不好"};
        for (String indicator : productIndicators) {
            int idx = question.indexOf(indicator);
            if (idx > 0) {
                return question.substring(0, idx).trim();
            }
        }
        // 提取第一个名词短语
        if (question.length() > 8) return question.substring(0, Math.min(question.length(), 20));
        return question;
    }

    /**
     * 从问题中提取地点
     */
    private String extractLocation(String question) {
        if (question == null) return "";
        // 常见地点指示词
        String[] indicators = {"在", "到", "去", "于", "的天气", "气温"};
        for (String ind : indicators) {
            int idx = question.indexOf(ind);
            if (idx >= 0) {
                String before = question.substring(0, idx).trim();
                String after = question.substring(idx + ind.length()).trim();
                // 取指示词前的地名（如果指示词在开头取后面）
                if (before.length() >= 2 && before.length() <= 10) return before;
                if (after.length() >= 2 && after.length() <= 10 && !after.contains("?")) return after;
            }
        }
        return question.replace("天气", "").replace("气温", "").replace("?", "").trim();
    }

}
