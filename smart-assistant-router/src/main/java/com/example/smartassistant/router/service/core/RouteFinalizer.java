/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.agent.AgentExecutionState;
import com.example.smartassistant.common.agent.AgentEventBus;
import com.example.smartassistant.common.agent.FeedbackLog;
import com.example.smartassistant.common.budget.BudgetTracker;
import com.example.smartassistant.common.observability.OpsMetrics;
import com.example.smartassistant.router.model.*;
import com.example.smartassistant.router.service.cache.SemanticRouteCacheService;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

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

    private final SemanticRouteCacheService semanticCache;
    private final OpsMetrics opsMetrics;
    private final RoutingToolChecker routingToolChecker;
    private final ReflectionService reflectionService;
    private final QualityEvaluationService qualityEvaluationService;
    private final ExperienceService experienceService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private AgentEventBus agentEventBus;

    @Autowired(required = false)
    private BadCaseMinerService badCaseMinerService;

    @Autowired(required = false)
    private BudgetTracker budgetTracker;

    @Autowired(required = false)
    private NewMetricsCollector newMetrics;

    public RouteFinalizer(
            SemanticRouteCacheService semanticCache,
            OpsMetrics opsMetrics,
            RoutingToolChecker routingToolChecker,
            ReflectionService reflectionService,
            QualityEvaluationService qualityEvaluationService,
            ExperienceService experienceService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.semanticCache = semanticCache;
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
     * 反思器质量评分 → LLM质量评估 → 语义缓存写入 → 经验提取 → 事件发布 → Bad Case 挖掘。
     */
    public RoutingResult finalizeRouting(RoutingResult result, RouteRequest request,
                                          String rawQuestion, EmotionCheckResult emotion) {
        String question = request.getQuestion();
        String intentTag = result.getIntentTag();
        if (intentTag == null || intentTag.isBlank()) {
            intentTag = semanticCache.generateIntentTag(question);
            result.setIntentTag(intentTag);
        }

        // ⭐ G4 运营指标
        opsMetrics.recordAnswer(result != null ? result.getAgentName() : "unknown", intentTag);

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
                && !Boolean.TRUE.equals(result.getFromCache())) {
            ReflectionResult reflection = reflectionService.evaluate(
                    question, result.getResult(), result.getAgentName(), intentTag, request.getUserId());
            reflectScore = reflection.getScore();
            if (!reflection.isAcceptable()) {
                log.warn("[Router] 🪞 反思不通过: score={}, agent={}, reason={}",
                        String.format("%.2f", reflection.getScore()),
                        result.getAgentName(), reflection.getReason());
                String retryResult = reflectionService.retry(
                        question, result.getResult(), result.getAgentName(),
                        intentTag, request.getUserId(), request.getRequestId());
                if (retryResult != null && !retryResult.equals(result.getResult())) {
                    result.setResult(retryResult);
                    log.info("[Router] 🪞 反思重试成功，已替换低质量回复");
                }
            }
        }

        // ⭐⭐ LLM-as-Judge 质量评估
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
            semanticCache.saveDecision(requestId, question, agentName,
                    result.getConfidence(), request.getUserId(), intentTag, request.getSessionId());
            semanticCache.saveExactMatch(rawQuestion != null ? rawQuestion : question, intentTag);

            if (!Boolean.TRUE.equals(result.getFromCache()) && qualityPassed) {
                if (reply != null && !reply.isBlank() && !reply.startsWith("❌") && !reply.startsWith("⚠️")
                        && reply.length() >= 20) {
                    semanticCache.saveReply(question, reply, agentName, intentTag,
                            Boolean.TRUE.equals(result.getAdminOperation()));
                }
            }

            if (!Boolean.TRUE.equals(result.getFromCache())) {
                experienceService.extractCommonExperience(question, agentName, intentTag);
                extractToolExperienceIfApplicable(reply, agentName, intentTag, question);
            }
        }

        // ⭐ P1 Agent 执行事件
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

        // ⭐ 完整决策写入 Redis
        if (requestId != null && !requestId.isBlank() && agentName != null) {
            semanticCache.saveFullDecisionForConsumer(requestId, agentName,
                    result.getConfidence(), reply, intentTag);
            appendTaskAnalysisToFullDecision(requestId);
        }

        // P1 ⭐ Bad Case 自动挖掘
        if (badCaseMinerService != null) {
            var badCaseDecision = new BadCaseMinerService.RoutingDecision(
                    request.getQuestion(), result.getIntentTag(),
                    result.getConfidence(), result.getAgentName(),
                    request.getSessionId(), request.getUserId());
            badCaseMinerService.record(badCaseDecision);
            badCaseMinerService.recordCorrection(badCaseDecision);
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

    /** 将任务分析结果追加到完整决策 JSON 中 */
    private void appendTaskAnalysisToFullDecision(String requestId) {
        if (redisTemplate == null || requestId == null || requestId.isBlank()) return;
        try {
            String analysisKey = "a2a:task-analysis:" + requestId;
            String analysisJson = redisTemplate.opsForValue().get(analysisKey);
            if (analysisJson == null) return;
            String fullKey = "a2a:route:full-decision:" + requestId;
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
            String key = "a2a:task-analysis:" + requestId;
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
            case "general_agent" -> {
                if (reply.contains("天气") || reply.contains("气温") || reply.contains("下雨")) {
                    experienceService.extractToolExperience(question, agentName, intentTag,
                            "getWeather", "{\"location\": \"" + QuestionExtractor.extractLocation(question) + "\"}", "{location}当前天气为{weather}");
                }
                if (reply.contains("新闻") || reply.contains("热点") || reply.contains("头条")) {
                    experienceService.extractToolExperience(question, agentName, intentTag,
                            "getHotNews", "{}", "以下是近期热点新闻");
                }
            }
        }
    }
}
