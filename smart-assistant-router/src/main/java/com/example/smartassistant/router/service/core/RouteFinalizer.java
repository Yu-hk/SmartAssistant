/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.routing.contract.RoutingKeys;

import com.example.smartassistant.common.agent.AgentExecutionState;
import com.example.smartassistant.common.agent.ExecutionTraceStore;
import com.example.smartassistant.common.agent.FeedbackLog;
import com.example.smartassistant.common.audit.TokenUsageCache;
import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.common.budget.BudgetTracker;
import com.example.smartassistant.common.intent.IntentTagGenerator;
import com.example.smartassistant.common.observability.OpsMetrics;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.router.model.*;
import com.example.smartassistant.router.service.evaluation.BadCaseMinerService;
import com.example.smartassistant.router.service.experience.ExperienceService;
import com.example.smartassistant.router.service.guardrail.EmotionCheckResult;
import com.example.smartassistant.router.service.monitoring.NewMetricsCollector;
import com.example.smartassistant.router.service.quality.QualityEvaluationService;
import com.example.smartassistant.router.service.tool.RoutingToolChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 路由后处理服务。
 * <p>
 * 从 RouterService 拆出，负责路由决策后的最终处理链路：
 * 反思器 → 质量评估 → 缓存写入 → 经验提取 → 事件发布 → Bad Case 挖掘。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-13
 */
@Service
public class RouteFinalizer {

    private static final Logger log = LoggerFactory.getLogger(RouteFinalizer.class);

    private final IntentTagGenerator intentTagGenerator;
    private final RoutingDecisionPublisher decisionPublisher;
    private final OpsMetrics opsMetrics;
    private final RoutingToolChecker routingToolChecker;
    private final ReflectionService reflectionService;
    private final QualityEvaluationService qualityEvaluationService;
    private final ExperienceService experienceService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${router.quality-evaluation.threshold:0.6}")
    private double qualityThreshold;

    /** Judge 故障时默认阻止未经验证的回答继续返回和进入缓存。 */
    @Value("${router.quality-evaluation.fail-closed:true}")
    private boolean qualityFailClosed;

    @Value("${router.quality-evaluation.failure-message:抱歉，我暂时无法可靠地回答这个问题。请补充更多信息或稍后重试。}")
    private String qualityFailureMessage;

    @Autowired(required = false)
    private ExecutionTraceStore executionTraceStore;

    @Autowired(required = false)
    private BadCaseMinerService badCaseMinerService;

    @Autowired(required = false)
    private BudgetTracker budgetTracker;

    @Autowired(required = false)
    private NewMetricsCollector newMetrics;

    @Autowired(required = false)
    @Qualifier("routerExperienceExecutor")
    private Executor experienceExecutor;

    public RouteFinalizer(
            IntentTagGenerator intentTagGenerator,
            RoutingDecisionPublisher decisionPublisher,
            OpsMetrics opsMetrics,
            RoutingToolChecker routingToolChecker,
            ReflectionService reflectionService,
            QualityEvaluationService qualityEvaluationService,
            ExperienceService experienceService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.intentTagGenerator = intentTagGenerator;
        this.decisionPublisher = decisionPublisher;
        this.opsMetrics = opsMetrics;
        this.routingToolChecker = routingToolChecker;
        this.reflectionService = reflectionService;
        this.qualityEvaluationService = qualityEvaluationService;
        this.experienceService = experienceService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ==================== 情绪干预 ====================

    /**
     * ⭐ 将情绪干预信息附加到路由结果
     */
    public RoutingResult applyEmotion(RoutingResult r, EmotionCheckResult e) {
        if (r == null || e == null || !e.triggered()) return r;
        return r.toBuilder()
                .emotionLevel(e.level())
                .emotionIntervention(true)
                .disableTools(e.disableTools())
                .emotionGuidance(e.guidance())
                .build();
    }

    // ==================== 路由后处理 ====================

    /**
     * ⭐⭐ 路由后处理公共方法：
     * 反思器质量评分 → LLM质量评估 → 路由经验提取 → 事件发布 → Bad Case 挖掘。
     */
    public RoutingResult finalizeRouting(RoutingResult result, RouteRequest request,
                                          String executionQuestion, EmotionCheckResult emotion) {
        String question = request.getQuestion();
        String evaluationQuestion = executionQuestion != null && !executionQuestion.isBlank()
                ? executionQuestion : question;
        String intentTag = result.getIntentTag();
        DomainQualityResult domainQuality = result.getDomainQuality() != null
                ? result.getDomainQuality() : DomainQualityResult.unknown();
        boolean clarification = Boolean.TRUE.equals(result.getClarification())
                || ClarificationReplyDetector.isRequiredParameterClarification(result.getResult());
        result.setClarification(clarification);
        normalizeRoutingMetadata(result, clarification);
        if (intentTag == null || intentTag.isBlank()) {
            intentTag = intentTagGenerator.generate(question);
            result.setIntentTag(intentTag);
        }

        // ⭐ G4 运营指标
        opsMetrics.recordAnswer(metricOwner(result), intentTag);

        // ⭐ P1 工具健康检查
        if (routingToolChecker != null && result.getAgentName() != null) {
            var health = routingToolChecker.checkAgentHealth(result.getAgentName());
            if (!health.isHealthy()) {
                log.warn("[Router] ⚠️ 路由到 Agent={} 但工具不健康: {}",
                        result.getAgentName(), health.getMessage());
            }
        }

        // ⭐⭐ 反思器
        double reflectScore = 0.7;
        if (result.getResult() != null && !result.getResult().isBlank()
                && result.getAgentName() != null && !"none".equals(result.getAgentName())
                && !clarification
                && !Boolean.TRUE.equals(result.getFromCache())
                && domainQuality.isUnknown()) {
            ReflectionResult reflection = reflectionService.evaluate(
                    evaluationQuestion, result.getResult(), result.getAgentName(), intentTag, request.getUserId());
            reflectScore = reflection.getScore();
            if (!reflection.isAcceptable()) {
                log.warn("[Router] 🪞 反思不通过: score={}, agent={}, reason={}",
                        String.format("%.2f", reflection.getScore()),
                        result.getAgentName(), reflection.getReason());
                String retryResult = reflectionService.retry(
                        evaluationQuestion, result.getResult(), result.getAgentName(),
                        intentTag, request.getUserId(), request.getRequestId());
                if (retryResult != null && !retryResult.equals(result.getResult())) {
                    result.setResult(retryResult);
                    log.info("[Router] 🪞 反思重试成功，已替换低质量回复");
                }

                // 重试可能替换回答，后续 Judge 必须使用最终回答的最新分数。
                ReflectionResult finalReflection = reflectionService.evaluate(
                        evaluationQuestion, result.getResult(), result.getAgentName(), intentTag, request.getUserId());
                reflectScore = finalReflection.getScore();
            }
        }

        // ⭐⭐ LLM-as-Judge 质量评估
        boolean qualityPassed = clarification || !domainQuality.isFail();
        String qualityFailureReason = !clarification && domainQuality.isFail()
                ? "DOMAIN_FAIL: " + domainQuality.reasonCodesHeaderValue() : null;
        if (!clarification && domainQuality.isFail()) {
            log.warn("[Router] Domain quality rejected response: agent={}, reasons={}",
                    result.getAgentName(), domainQuality.getReasonCodes());
        }

        boolean judgeEligible = result.getAgentName() != null
                || result.getExecutionMode() == RoutingResult.ExecutionMode.MULTI_AGENT;
        boolean requiresGlobalJudge = !clarification && judgeEligible
                && (domainQuality.isUnknown() || domainQuality.isWarn());
        if (requiresGlobalJudge && result.getResult() != null && !result.getResult().isBlank()
                && !Boolean.TRUE.equals(result.getFromCache())) {
            double judgeTriggerScore = domainQuality.isWarn() ? 0.7 : reflectScore;
            QualityEvaluationResult quality = qualityEvaluationService.evaluate(
                    evaluationQuestion, result.getResult(), judgeTriggerScore);
            if ((quality.isCompleted() && !quality.isPassing(qualityThreshold))
                    || (quality.isFailed() && qualityFailClosed)) {
                qualityPassed = false;
                log.warn("[Router] 🔍 质量评估未放行: status={}, overall={}, hallucination={}, reason={}",
                        quality.getStatus(),
                        String.format("%.2f", quality.getOverall()),
                        String.format("%.2f", quality.getHallucination()),
                        quality.getReason());
                qualityFailureReason = quality.getStatus() + ": " + quality.getReason();
                result.setResult(qualityFailureMessage);
            }
        }

        // ⭐ 缓存路由决策 + 回复
        String agentName = result.getAgentName();
        String requestId = request.getRequestId();
        String reply = result.getResult();

        if (!clarification
                && agentName != null && !"none".equals(agentName) && !agentName.isBlank()) {
            if (!Boolean.TRUE.equals(result.getFromCache()) && qualityPassed) {
                learnExperienceAsync(question, agentName, intentTag);
                extractToolExperienceIfApplicable(reply, agentName, intentTag, question);
            }
        }

        // ⭐ P1 Agent 执行事件
        if (executionTraceStore != null) {
            executionTraceStore.publishEvent(
                    request.getRequestId(), result.getAgentName(),
                    AgentExecutionState.State.RUNNING,
                    result.getResult() != null ? AgentExecutionState.State.COMPLETED
                            : AgentExecutionState.State.FAILED,
                    AgentExecutionState.EventType.EXECUTION_COMPLETED,
                    "路由决策完成, agent=" + result.getAgentName()
                            + ", executionMode=" + result.getExecutionMode()
                            + ", participatingAgents=" + result.getParticipatingAgents()
                            + ", confidence=" + result.getConfidence() + ", intent=" + intentTag,
                    0, 0
            );
        }

        // ⭐ 完整决策写入 Redis
        if (requestId != null && !requestId.isBlank()) {
            decisionPublisher.publish(requestId, result,
                    TokenUsageCache.snapshot(requestId),
                    ToolUsageCache.snapshot(requestId));
            appendTaskAnalysisToFullDecision(requestId);
        }

        // P1 ⭐ Bad Case 自动挖掘
        if (!clarification && badCaseMinerService != null) {
            var badCaseDecision = new BadCaseMinerService.RoutingDecision(
                    request.getQuestion(), result.getIntentTag(),
                    result.getConfidence(), result.getAgentName(),
                    request.getSessionId(), request.getUserId());
            badCaseMinerService.record(badCaseDecision);
            badCaseMinerService.recordCorrection(badCaseDecision);
            if (!qualityPassed) {
                badCaseMinerService.recordQualityFailure(badCaseDecision, qualityFailureReason);
            }
        }

        // ⭐ Agent 反馈模式监控
        var patternCounts = com.example.smartassistant.common.agent.FeedbackLog.getPatternCountsSnapshot();
        if (!patternCounts.isEmpty()) {
            log.debug("[Router] Agent 反馈模式统计: {}", patternCounts);
        }

        // ⭐ P1 预算追踪
        if (budgetTracker != null) {
            var budgetStatus = budgetTracker.checkSession();
            if (budgetStatus.exceeded()) {
                log.warn("[Router] ⚠️ 会话预算超限: {}", budgetStatus.reason());
                if (newMetrics != null) newMetrics.recordBudgetExceeded();
            }
            budgetTracker.endSession();
        }

        // ⭐ P4-A 情绪干预
        return applyEmotion(result, emotion);
    }

    // ==================== 内部方法 ====================

    private void learnExperienceAsync(String question, String agentName, String intentTag) {
        if (experienceExecutor == null) {
            // Preserve deterministic behavior in lightweight tests and minimal deployments.
            experienceService.extractCommonExperience(question, agentName, intentTag);
            return;
        }
        try {
            experienceExecutor.execute(() -> {
                try {
                    experienceService.extractCommonExperience(question, agentName, intentTag);
                } catch (Exception error) {
                    log.warn("[Router] 异步经验提取失败: agent={}, error={}",
                            agentName, error.getMessage());
                }
            });
            log.debug("[Router] 经验提取已转入后台: agent={}, intent={}", agentName, intentTag);
        } catch (RejectedExecutionException rejected) {
            log.warn("[Router] 经验提取队列已满，本次跳过: agent={}, intent={}", agentName, intentTag);
        }
    }

    private static String metricOwner(RoutingResult result) {
        if (result == null) return "unknown";
        if (result.getAgentName() != null && !result.getAgentName().isBlank()) {
            return result.getAgentName();
        }
        return result.getExecutionMode() != null
                ? result.getExecutionMode().name().toLowerCase(java.util.Locale.ROOT)
                : "unknown";
    }

    private static void normalizeRoutingMetadata(RoutingResult result, boolean clarification) {
        if (result.getParticipatingAgents() == null) {
            result.setParticipatingAgents(java.util.List.of());
        }
        if (result.getAgentName() != null && !result.getAgentName().isBlank()
                && result.getParticipatingAgents().isEmpty()) {
            result.setParticipatingAgents(java.util.List.of(result.getAgentName()));
        }
        if (result.getAgentName() == null
                && result.getExecutionMode() == RoutingResult.ExecutionMode.SINGLE_AGENT) {
            result.setExecutionMode(RoutingResult.ExecutionMode.BUILTIN);
        }
        if (clarification
                && result.getWorkflowStatus() == RoutingResult.WorkflowStatus.COMPLETED) {
            result.setWorkflowStatus(RoutingResult.WorkflowStatus.CLARIFICATION);
        }
    }

    /** 将任务分析结果追加到完整决策 JSON 中 */
    private void appendTaskAnalysisToFullDecision(String requestId) {
        if (redisTemplate == null || requestId == null || requestId.isBlank()) return;
        try {
            String analysisKey = RoutingKeys.taskAnalysis(requestId);
            String analysisJson = redisTemplate.opsForValue().get(analysisKey);
            if (analysisJson == null) return;
            String fullKey = RoutingKeys.fullDecision(requestId);
            String fullJson = redisTemplate.opsForValue().get(fullKey);
            if (fullJson == null) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> fullMap = objectMapper.readValue(fullJson, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> analysisMap = objectMapper.readValue(analysisJson, Map.class);
            fullMap.put("taskAnalysis", analysisMap);
            redisTemplate.opsForValue().set(fullKey, objectMapper.writeValueAsString(fullMap),
                    java.time.Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("[Router] 追加任务分析到决策失败: {}", e.getMessage());
        }
    }

    /** 将任务分析结果存储到 Redis */
    public void storeTaskAnalysisToRedis(String requestId, TaskAnalysisResult analysis) {
        if (requestId == null || requestId.isBlank() || redisTemplate == null) return;
        try {
            String key = RoutingKeys.taskAnalysis(requestId);
            String json = objectMapper.writeValueAsString(analysis);
            redisTemplate.opsForValue().set(key, json, 120, java.util.concurrent.TimeUnit.SECONDS);
            log.info("[RouteFinalizer] 🔍 任务分析已存储: requestId={}, intent={}, entities={}",
                    requestId, analysis.getIntentCategory(), analysis.getEntities().size());
        } catch (Exception e) {
            log.warn("[RouteFinalizer] 存储任务分析失败: {}", e.getMessage());
        }
    }

    /** 提取 TOOL 经验 */
    private void extractToolExperienceIfApplicable(String reply, String agentName,
                                                    String intentTag, String question) {
        if (reply == null || agentName == null || intentTag == null) return;
        switch (agentName) {
            case "order_agent" -> {
                if (reply.contains("订单") || reply.contains("物流") || reply.contains("配送")) {
                    String params = QuestionExtractor.extractOrderParams(question);
                    experienceService.extractToolExperience(question, agentName, intentTag,
                            "queryOrder", params, "订单{orderId}当前状态为{status}");
                }
                if (reply.contains("退款") || reply.contains("退货")) {
                    experienceService.extractToolExperience(question, agentName, intentTag,
                            "refundOrder", "{\"orderId\": \"" + QuestionExtractor.extractOrderId(question) + "\"}", "退款申请已提交");
                }
            }
            case "product_agent" -> {
                if (reply.contains("价格") || reply.contains("多少钱") || reply.contains("报价")) {
                    experienceService.extractToolExperience(question, agentName, intentTag,
                            "queryPrice", "{\"product\": \"" + QuestionExtractor.extractProductName(question) + "\"}", "{product}的价格为{price}");
                }
                if (reply.contains("库存") || reply.contains("有货") || reply.contains("缺货")) {
                    experienceService.extractToolExperience(question, agentName, intentTag,
                            "checkStock", "{\"product\": \"" + QuestionExtractor.extractProductName(question) + "\"}", "{product}的库存状态为{status}");
                }
            }
            case "general_agent", "router_fallback" -> {
                if (reply.contains("新闻") || reply.contains("热点") || reply.contains("头条")) {
                    experienceService.extractToolExperience(question, agentName, intentTag,
                            "getHotNews", "{}", "以下是近期热点新闻");
                }
            }
        }
    }
}
