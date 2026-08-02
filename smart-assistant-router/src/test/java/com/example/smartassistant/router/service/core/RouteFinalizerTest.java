package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.observability.OpsMetrics;
import com.example.smartassistant.router.model.QualityEvaluationResult;
import com.example.smartassistant.router.model.ReflectionResult;
import com.example.smartassistant.router.model.RouteRequest;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.router.service.cache.SemanticRouteCacheService;
import com.example.smartassistant.router.service.experience.ExperienceService;
import com.example.smartassistant.router.service.quality.QualityEvaluationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RouteFinalizerTest {

    @Test
    void reflectionAndQuality_shouldEvaluateOnlyCurrentTurn() {
        SemanticRouteCacheService semanticCache = mock(SemanticRouteCacheService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        QualityEvaluationService qualityService = mock(QualityEvaluationService.class);
        ExperienceService experienceService = mock(ExperienceService.class);
        when(reflectionService.evaluate(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new ReflectionResult(true, 1.0, "质量合格"));
        when(qualityService.evaluate(anyString(), anyString(), anyDouble()))
                .thenReturn(new QualityEvaluationResult(
                        1.0, 1.0, 1.0, 1.0, 1.0, "完整", "completed"));
        RouteFinalizer finalizer = new RouteFinalizer(
                semanticCache, mock(OpsMetrics.class), null, reflectionService,
                qualityService, experienceService, null, new ObjectMapper(), Runnable::run);
        String fullPrompt = "【当前问题】\nORD-LOAD000001001"
                + "\n【历史对话】\n用户：查询我的订单物流进度"
                + "\n助手：也可以查询物流轨迹或售后资格。";
        String currentQuestion = "ORD-LOAD000001001";
        String reply = "订单 ORD-LOAD000001001 当前状态为待付款，付款后才会安排发货。";
        RoutingResult result = RoutingResult.builder()
                .result(reply)
                .agentName("order_agent")
                .confidence(0.99)
                .intentTag("订单查询")
                .routingMethod("KEYWORD_FAST_ROUTE")
                .fromCache(false)
                .build();
        RouteRequest request = RouteRequest.builder()
                .userId(1L)
                .question(fullPrompt)
                .requestId("request-2")
                .sessionId("session-a")
                .build();

        finalizer.finalizeRouting(result, request, currentQuestion, null);

        verify(reflectionService).evaluate(eq(currentQuestion), eq(reply),
                eq("order_agent"), eq("订单查询"), eq(1L));
        verify(qualityService).evaluate(eq(currentQuestion), eq(reply), eq(1.0));
        verify(reflectionService, never()).retry(anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyString());
    }

    @Test
    void rejectedReflection_shouldSkipReplyCacheAndExperienceExtraction() {
        SemanticRouteCacheService semanticCache = mock(SemanticRouteCacheService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        QualityEvaluationService qualityService = mock(QualityEvaluationService.class);
        ExperienceService experienceService = mock(ExperienceService.class);

        String question = "审计订单 ORD-2024001，并判断是否满足取消条件。核验标记 NC-TRACE-001";
        String incompleteReply = "订单 ORD-2024001 当前状态为已发货，但未提供取消条件判断。";
        when(reflectionService.evaluate(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new ReflectionResult(false, 0.83,
                        "关键要求未覆盖(取消条件判断、核验标记)"));
        when(reflectionService.retry(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(incompleteReply);
        when(qualityService.evaluate(anyString(), anyString(), anyDouble()))
                .thenReturn(new QualityEvaluationResult(
                        0.9, 0.5, 0.9, 0.7, 0.7, "部分完整", "completed"));

        RouteFinalizer finalizer = new RouteFinalizer(
                semanticCache,
                mock(OpsMetrics.class),
                null,
                reflectionService,
                qualityService,
                experienceService,
                null,
                new ObjectMapper(),
                Runnable::run);
        RoutingResult result = RoutingResult.builder()
                .result(incompleteReply)
                .agentName("order_agent")
                .confidence(0.95)
                .intentTag("订单查询")
                .routingMethod("KEYWORD_FAST_ROUTE")
                .fromCache(false)
                .build();
        RouteRequest request = RouteRequest.builder()
                .userId(1L)
                .question(question)
                .requestId("req-cache-gate")
                .sessionId("session-1")
                .build();

        finalizer.finalizeRouting(result, request, question, null);

        verify(semanticCache, never()).saveReply(
                anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(experienceService, never()).extractCommonExperience(
                anyString(), anyString(), anyString());
        verify(experienceService, never()).extractToolExperience(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(semanticCache).saveDecision(
                anyString(), anyString(), anyString(), anyDouble(), anyLong(), anyString(), anyString(), eq(false));
    }

    @Test
    void cancellationAdvice_shouldNotBeRecordedAsRefundToolExecution() {
        SemanticRouteCacheService semanticCache = mock(SemanticRouteCacheService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        QualityEvaluationService qualityService = mock(QualityEvaluationService.class);
        ExperienceService experienceService = mock(ExperienceService.class);

        when(reflectionService.evaluate(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new ReflectionResult(true, 1.0, "质量合格"));
        when(qualityService.evaluate(anyString(), anyString(), anyDouble()))
                .thenReturn(new QualityEvaluationResult(
                        1.0, 1.0, 1.0, 1.0, 1.0, "完整", "completed"));

        RouteFinalizer finalizer = new RouteFinalizer(
                semanticCache,
                mock(OpsMetrics.class),
                null,
                reflectionService,
                qualityService,
                experienceService,
                null,
                new ObjectMapper(),
                Runnable::run);
        String question = "查询订单 ORD-2024001 是否满足取消条件";
        String reply = "订单 ORD-2024001 已发货，不满足直接取消条件，"
                + "如不再需要请按退货退款流程处理。";
        RoutingResult result = RoutingResult.builder()
                .result(reply)
                .agentName("order_agent")
                .confidence(0.95)
                .intentTag("订单查询")
                .routingMethod("KEYWORD_FAST_ROUTE")
                .fromCache(false)
                .build();
        RouteRequest request = RouteRequest.builder()
                .userId(1L)
                .question(question)
                .requestId("req-no-fake-refund")
                .sessionId("session-2")
                .build();

        finalizer.finalizeRouting(result, request, question, null);

        verify(experienceService, never()).extractToolExperience(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(experienceService, never()).extractToolExperience(
                anyString(), anyString(), anyString(),
                eq("refundOrder"), anyString(), anyString());
    }
}
