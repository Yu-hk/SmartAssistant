/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.agent.AgentEventBus;
import com.example.smartassistant.common.agent.AgentExecutionState;
import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.router.service.fusion.IntentFusionResult;
import com.example.smartassistant.router.service.fusion.IntentFusionService;
import com.example.smartassistant.common.error.ErrorRecoveryService;
import com.example.smartassistant.router.model.*;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.cache.SemanticRouteCacheService;
import com.example.smartassistant.router.service.evaluation.BadCaseMinerService;
import com.example.smartassistant.router.service.evaluation.IntentGuidedQueryRewriter;
import com.example.smartassistant.router.service.experience.ExperienceService;
import com.example.smartassistant.router.service.quality.QualityEvaluationService;
import com.example.smartassistant.router.service.rag.RouterRagService;
import com.example.smartassistant.router.service.routing.KeywordFastRouteService;
import com.example.smartassistant.router.service.taskanalysis.TaskAnalysisService;
import com.example.smartassistant.router.service.tool.RoutingToolChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Executor;
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
    private final ChatClient chatClient;
    private final StringRedisTemplate redisTemplate;
    private final RouterRagService ragService;
    private final SemanticRouteCacheService semanticCache;
    private final TaskPlannerService taskPlanner;
    private final ResultMerger resultMerger;
    private final ReflectionService reflectionService;
    private final ModelRoutingService modelRoutingService;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ⭐ 并行 Agent 执行线程池（用于独立子任务的真正并行执行）
    private final Executor parallelExecutor;

    // ⭐ 子任务单 Agent 超时（毫秒）
    @Value("${router.parallel.agent-timeout-ms:30000}")
    private long agentTimeoutMs;

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

    // ⭐ 关键词快车道服务（P2 改进：高频明确意图跳过 LLM 分诊）
    private final KeywordFastRouteService keywordFastRouteService;
    // ⭐ 路由级工具健康检查
    private final RoutingToolChecker routingToolChecker;

    public RouterService(AgentCallerService agentCallerService,
                         ChatClient.Builder chatClientBuilder,
                         @Qualifier("routerParallelAgentExecutor") Executor routerParallelAgentExecutor,
                         @Autowired(required = false) StringRedisTemplate redisTemplate,
                         RouterRagService ragService,
                         SemanticRouteCacheService semanticCache,
                         TaskPlannerService taskPlanner,
                         ResultMerger resultMerger,
                         ReflectionService reflectionService,
                         ModelRoutingService modelRoutingService,
                         ExperienceService experienceService,
                         GraphExecutionService graphExecutionService,
                         TaskAnalysisService taskAnalysisService,
                         QualityEvaluationService qualityEvaluationService,
                         IntentGuidedQueryRewriter queryRewriter,
                         KeywordFastRouteService keywordFastRouteService,
                         RoutingToolChecker routingToolChecker,
                         @Qualifier("lightChatModel") ChatModel lightModel,
                         @Autowired(required = false) BadCaseMinerService badCaseMinerService) {
        this.agentCallerService = agentCallerService;
        this.chatClient = chatClientBuilder.build();
        this.redisTemplate = redisTemplate;
        this.ragService = ragService;
        this.semanticCache = semanticCache;
        this.taskPlanner = taskPlanner;
        this.resultMerger = resultMerger;
        this.reflectionService = reflectionService;
        this.modelRoutingService = modelRoutingService;
        this.experienceService = experienceService;
        this.graphExecutionService = graphExecutionService;
        this.taskAnalysisService = taskAnalysisService;
        this.qualityEvaluationService = qualityEvaluationService;
        this.queryRewriter = queryRewriter;
        this.keywordFastRouteService = keywordFastRouteService;
        this.routingToolChecker = routingToolChecker;
        this.lightChatClient = ChatClient.create(lightModel);
        this.parallelExecutor = routerParallelAgentExecutor;
        this.badCaseMinerService = badCaseMinerService;
    }
    
    /**
     * 执行路由决策并调用目标 Agent（统一入口）
     * 只识别并处理第一个意图
     */
    public RoutingResult route(RouteRequest request) {
        log.info("[Router] 收到路由请求: userId={}, sessionId={}, question={}",
                request.getUserId(), request.getSessionId(), truncate(request.getQuestion(), 100));

        try {
            // Step 0: 经验匹配（优先级最高，在语义缓存之上）
            // ⭐ 经验匹配可直接跳过 LLM 推理，命中 TOOL 经验时甚至直接执行工具
            String question = request.getQuestion();
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
                
                // TOOL 经验：直接把 reroutedQuestion 发往目标 Agent
                if (experienceMatch.isToolExperience) {
                    String agentReply = agentCallerService.callAgent(
                            experienceMatch.agentName,
                            experienceMatch.reroutedQuestion,
                            request.getUserId(),
                            request.getRequestId());
                    if (agentReply != null && !agentReply.isBlank()) {
                        RoutingResult result = RoutingResult.builder()
                                .result(agentReply)
                                .agentName(experienceMatch.agentName)
                                .confidence(experienceMatch.matchScore)
                                .intentTag(experienceMatch.experience.getIntentTag())
                                .build();
                        return finalizeRouting(result, request, question);
                    }
                }
                
                // COMMON / REACT 经验：设置路由目标，跳过 TaskPlanner
                if (experienceMatch.skipTaskPlanning) {
                    String agentReply = agentCallerService.callAgent(
                            experienceMatch.agentName,
                            experienceMatch.reroutedQuestion != null ? 
                                    experienceMatch.reroutedQuestion : question,
                            request.getUserId(),
                            request.getRequestId());
                    if (agentReply != null && !agentReply.isBlank()) {
                        RoutingResult result = RoutingResult.builder()
                                .result(agentReply)
                                .agentName(experienceMatch.agentName)
                                .confidence(experienceMatch.matchScore)
                                .intentTag(experienceMatch.experience.getIntentTag())
                                .build();
                        return finalizeRouting(result, request, question);
                    }
                }
            }

            // Step 0.5: 关键词快车道（P2 改进：高频明确意图跳过 LLM 分诊）
            // 优先级：经验匹配 > 关键词快车道 > 语义缓存 > LLM 意图识别
            KeywordFastRouteService.MatchResult keywordMatch = keywordFastRouteService.match(question);
            if (keywordMatch != null) {
                log.info("[Router] ⚡ 关键词快车道命中: rule={}, agent={}, intent={}, confidence={}",
                        keywordMatch.getMatchedRuleName(), keywordMatch.getTargetAgent(),
                        keywordMatch.getIntentTag(), keywordMatch.getConfidence());
                // 直接调用目标 Agent
                String agentReply = agentCallerService.callAgent(
                        keywordMatch.getTargetAgent(),
                        question,
                        request.getUserId(),
                        request.getRequestId());
                if (agentReply != null && !agentReply.isBlank()) {
                    RoutingResult result = RoutingResult.builder()
                            .result(agentReply)
                            .agentName(keywordMatch.getTargetAgent())
                            .confidence(keywordMatch.getConfidence())
                            .intentTag(keywordMatch.getIntentTag())
                            .build();
                    return finalizeRouting(result, request, question);
                }
            }

            // Step 1: 检查语义缓存（仅存 agent 名，不存回复内容）
            SemanticRouteCacheService.CachedRouteDecision cached = semanticCache.getCachedDecision(question);
            
            // Step 2: 构建上下文
            Map<String, Object> context = buildContext(request);

            // Step 3: RAG 增强(可选)
            String enhancedQuestion = question;
            if (ragEnabled && Boolean.TRUE.equals(request.getEnableRag())) {
                @SuppressWarnings("unchecked")
                List<String> history = (List<String>) context.get("conversationHistory");
                enhancedQuestion = ragService.enhanceQuestion(
                        request.getQuestion(),
                        history
                );
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
                    if (cached.agentName != null && !"none".equals(cached.agentName)) {
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
                        confidence = taskAnalysis.getConfidence();
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

        // ⭐ P1 工具健康检查：路由到 Agent 前检查关键工具是否就绪
        if (result.getAgentName() != null) {
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
        if ("order_agent".equals(agentName)) {
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
        else if ("product_agent".equals(agentName)) {
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
        else if ("general_agent".equals(agentName)) {
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

        // Step 1: 图分解（带依赖关系的 DAG）
        IntentGraph graph = taskPlanner.planToGraph(question);
        if (graph.getNodeCount() == 0) {
            log.warn("[Collaborative] 图分解为空，降级到内联 ChatClient 兜底");
            return inlineFallback(question);
        }
        log.info("[Collaborative] 图分解完成: {} 个节点, hasDeps={}, maxParallelism={}",
                graph.getNodeCount(), graph.hasDependency(), graph.getMaxParallelism());

        String eventsKey = requestId != null ? SSE_EVENTS_KEY_PREFIX + requestId : null;

        // Step 2: 图执行（根据拓扑并行执行，支持 Handoff 显式交接）
        List<SubTaskResult> results = graphExecutionService.executeWithHandoff(graph, userId, eventsKey, requestId);

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

    /**
     * 多 Agent 顺序执行带共享上下文累积。
     * <p>
     * <b>已弃用</b>：请使用 {@link GraphExecutionService#execute} 替代。
     * 保留此方法仅用于后向兼容和回退参考。
     * </p>
     *
     * @deprecated 使用 {@link GraphExecutionService#execute(IntentGraph, Long, String, String)} 替代
     */
    @Deprecated
    private List<SubTaskResult> executeTasksWithSharedContext(List<SubTask> tasks, Long userId, String eventsKey, String requestId) {
        List<SubTaskResult> results = new ArrayList<>();
        StringBuilder sharedContext = new StringBuilder();

        for (SubTask task : tasks) {
            // 将已有共享上下文附加到子任务描述中
            String enrichedDesc = task.getDescription();
            if (!sharedContext.isEmpty()) {
                enrichedDesc = enrichedDesc + "\n\n[已知信息]\n" + sharedContext.toString().trim()
                        + "\n\n请结合以上已知信息回答，避免重复，侧重补充新内容。";
            }

            storeSseEvent(eventsKey, "routed",
                    "🎯 正在处理: " + task.getDescription(), task.getTargetAgent());

            try {
                var agentResult = agentCallerService.callAgentAndExtractTitles(
                        task.getTargetAgent(), enrichedDesc, userId, requestId);
                String resultText = agentResult.getResponse();
                if (resultText != null && !resultText.isBlank()) {
                    // 将结果加入共享上下文，供后续 Agent 使用
                    sharedContext.append("【").append(task.getTargetAgent()).append("】")
                            .append(resultText).append("\n\n");
                    results.add(new SubTaskResult(task.getId(), task.getDescription(),
                            task.getTargetAgent(), resultText, true, agentResult.getRealTitles(), agentResult.getTagsByTitle()));
                    storeSseEvent(eventsKey, "response",
                            resultText.substring(0, Math.min(200, resultText.length())),
                            task.getTargetAgent());
                } else {
                    results.add(new SubTaskResult(task.getId(), task.getDescription(),
                            task.getTargetAgent(), "", false, agentResult.getRealTitles()));
                }
            } catch (Exception e) {
                log.warn("[Collaborative] 子任务失败: task={}, error={}", task.getId(), e.getMessage());
                results.add(new SubTaskResult(task.getId(), task.getDescription(),
                        task.getTargetAgent(), "", false));
            }
        }
        return results;
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
        try {
            String localReply = lightChatClient.prompt()
                    .system("你是一个温暖、耐心的助手。用户已经等待了一段时间，可能有些着急了。"
                          + "请用温和友善的语气回应，先为等待道歉，然后安抚情绪。"
                          + "如果实在无法处理当前问题，诚恳地请用户稍后再试，"
                          + "不要引导用户去尝试其他功能（因为那些功能可能也暂时不可用）。")
                    .user(question)
                    .call()
                    .content();
            if (localReply != null && !localReply.isBlank()) {
                return RoutingResult.builder()
                        .result(localReply)
                        .agentName("builtin_fallback")
                        .confidence(0.2)
                        .build();
            }
        } catch (Exception e) {
            log.warn("[Router] 本地推理兜底失败: {}", e.getMessage());
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
