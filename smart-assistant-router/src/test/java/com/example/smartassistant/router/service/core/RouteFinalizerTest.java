package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.observability.OpsMetrics;
import com.example.smartassistant.common.location.DeviceLocation;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.router.model.QualityEvaluationResult;
import com.example.smartassistant.router.model.ReflectionResult;
import com.example.smartassistant.router.model.RouteRequest;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.router.service.cache.SemanticRouteCacheService;
import com.example.smartassistant.router.service.evaluation.BadCaseMinerService;
import com.example.smartassistant.router.service.experience.ExperienceService;
import com.example.smartassistant.router.service.quality.QualityEvaluationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteFinalizerTest {

    @Mock SemanticRouteCacheService semanticCache;
    @Mock OpsMetrics opsMetrics;
    @Mock ReflectionService reflectionService;
    @Mock QualityEvaluationService qualityEvaluationService;
    @Mock ExperienceService experienceService;
    @Mock BadCaseMinerService badCaseMinerService;

    RouteFinalizer finalizer;

    @BeforeEach
    void setUp() {
        finalizer = new RouteFinalizer(semanticCache, opsMetrics, null,
                reflectionService, qualityEvaluationService, experienceService,
                null, new ObjectMapper());
        ReflectionTestUtils.setField(finalizer, "qualityThreshold", 0.6);
        ReflectionTestUtils.setField(finalizer, "qualityFailClosed", true);
        ReflectionTestUtils.setField(finalizer, "qualityFailureMessage", "安全降级回复");
        ReflectionTestUtils.setField(finalizer, "badCaseMinerService", badCaseMinerService);
    }

    private static RouteRequest request() {
        return RouteRequest.builder().userId(7L).question("查询订单状态")
                .sessionId("s1").requestId("r1").build();
    }

    private static RoutingResult result(String reply) {
        return RoutingResult.builder().result(reply).agentName("order")
                .intentTag("order_query").confidence(0.9).fromCache(false).build();
    }

    @Test
    void retryUsesFreshReflectionScoreForJudge() {
        RoutingResult routing = result("低质量回答");
        when(reflectionService.evaluate(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new ReflectionResult(false, 0.4, "too low"))
                .thenReturn(new ReflectionResult(true, 0.82, "retry pass"));
        when(reflectionService.retry(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn("改进后的订单状态回答，包含足够且可靠的信息。");
        when(qualityEvaluationService.evaluate(anyString(), anyString(), anyDouble()))
                .thenReturn(QualityEvaluationResult.skipped());

        finalizer.finalizeRouting(routing, request(), "查询订单状态", null);

        verify(qualityEvaluationService).evaluate(
                "查询订单状态", "改进后的订单状态回答，包含足够且可靠的信息。", 0.82);
    }

    @Test
    void lowQualityAnswerIsReplacedAndNeverCachedOrLearned() {
        RoutingResult routing = result("看似完整但事实不可靠的回答内容。");
        when(reflectionService.evaluate(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new ReflectionResult(true, 0.7, "rule pass"));
        when(qualityEvaluationService.evaluate(anyString(), anyString(), anyDouble()))
                .thenReturn(new QualityEvaluationResult(0.8, 0.7, 0.2, 0.7,
                        0.5, "存在幻觉", "{}"));

        RoutingResult finalized = finalizer.finalizeRouting(routing, request(), "查询订单状态", null);

        assertEquals("安全降级回复", finalized.getResult());
        verify(semanticCache, never()).saveReply(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(experienceService, never()).extractCommonExperience(anyString(), anyString(), anyString());
        verify(badCaseMinerService).recordQualityFailure(any(),
                org.mockito.ArgumentMatchers.contains("存在幻觉"));
    }

    @Test
    void judgeFailureFailsClosed() {
        RoutingResult routing = result("未经 Judge 验证的回答内容。");
        when(reflectionService.evaluate(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new ReflectionResult(true, 0.7, "rule pass"));
        when(qualityEvaluationService.evaluate(anyString(), anyString(), anyDouble()))
                .thenReturn(QualityEvaluationResult.failed("timeout"));

        RoutingResult finalized = finalizer.finalizeRouting(routing, request(), "查询订单状态", null);

        assertEquals("安全降级回复", finalized.getResult());
        verify(semanticCache, never()).saveReply(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void evaluatesAndRetriesAgainstExecutionQuestion() {
        RoutingResult routing = result("数据库中未找到相关的信息。");
        when(reflectionService.evaluate(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new ReflectionResult(false, 0.3, "knowledge miss"))
                .thenReturn(new ReflectionResult(true, 0.9, "retry pass"));
        when(reflectionService.retry(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn("办公笔记本优先关注处理器、内存、硬盘和续航。");
        when(qualityEvaluationService.evaluate(anyString(), anyString(), anyDouble()))
                .thenReturn(QualityEvaluationResult.skipped());

        finalizer.finalizeRouting(routing, request(), "办公笔记本电脑选购重点指标", null);

        verify(reflectionService).retry(
                org.mockito.ArgumentMatchers.eq("办公笔记本电脑选购重点指标"),
                anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(qualityEvaluationService).evaluate(
                "办公笔记本电脑选购重点指标", "办公笔记本优先关注处理器、内存、硬盘和续航。", 0.9);
        verify(semanticCache).saveExactMatch("查询订单状态", "order_query");
    }

    @Test
    void domainPassSkipsDuplicateReflectionAndJudge() {
        RoutingResult routing = result("订单 ORD-1001 已发货，预计明天送达。");
        routing.setDomainQuality(DomainQualityResult.pass(0.92, "ORDER_FACTS_VERIFIED"));

        finalizer.finalizeRouting(routing, request(), "查询 ORD-1001 的状态", null);

        verify(reflectionService, never()).evaluate(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(qualityEvaluationService, never()).evaluate(anyString(), anyString(), anyDouble());
        verify(semanticCache).saveReply(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void domainFailPreservesSafeFallbackAndBlocksLearning() {
        RoutingResult routing = result("暂时无法核实该订单状态，请稍后重试。");
        routing.setDomainQuality(DomainQualityResult.fail("ORDER_STATUS_MISMATCH"));

        RoutingResult finalized = finalizer.finalizeRouting(
                routing, request(), "查询 ORD-1001 的状态", null);

        assertEquals("暂时无法核实该订单状态，请稍后重试。", finalized.getResult());
        verify(reflectionService, never()).evaluate(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(qualityEvaluationService, never()).evaluate(anyString(), anyString(), anyDouble());
        verify(semanticCache, never()).saveReply(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(experienceService, never()).extractCommonExperience(anyString(), anyString(), anyString());
        verify(badCaseMinerService).recordQualityFailure(any(),
                org.mockito.ArgumentMatchers.contains("ORDER_STATUS_MISMATCH"));
    }

    @Test
    void requiredParameterClarificationSkipsQualityAndFailureCaches() {
        RoutingResult routing = RoutingResult.builder()
                .result("请告诉我想查询哪个城市的天气，例如“北京天气”。")
                .agentName("general")
                .intentTag("weather_query")
                .confidence(1.0)
                .build();

        RoutingResult finalized = finalizer.finalizeRouting(
                routing, request(), "查询天气", null);

        assertEquals("请告诉我想查询哪个城市的天气，例如“北京天气”。", finalized.getResult());
        assertEquals(true, finalized.getClarification());
        verify(reflectionService, never()).evaluate(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(qualityEvaluationService, never()).evaluate(anyString(), anyString(), anyDouble());
        verify(semanticCache, never()).saveDecision(anyString(), anyString(), anyString(),
                anyDouble(), anyLong(), anyString(), anyString());
        verify(semanticCache, never()).saveReply(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(badCaseMinerService, never()).recordQualityFailure(any(), anyString());
    }

    @Test
    void deviceLocationWeatherIsNeverStoredInSemanticCacheOrExperience() {
        RouteRequest locationRequest = RouteRequest.builder()
                .userId(7L)
                .question("查询天气")
                .sessionId("s-weather")
                .requestId("r-weather")
                .deviceLocation(new DeviceLocation(
                        39.9042, 116.4074, 1000d, System.currentTimeMillis()))
                .build();
        RoutingResult routing = RoutingResult.builder()
                .result("当前位置晴朗，温度 28°C，未来三天以晴天为主。")
                .agentName("general")
                .intentTag("weather_query")
                .confidence(1.0)
                .domainQuality(DomainQualityResult.pass(0.95, "WEATHER_DATA_VERIFIED"))
                .build();

        finalizer.finalizeRouting(routing, locationRequest, "查询天气", null);

        verify(semanticCache, never()).saveDecision(anyString(), anyString(), anyString(),
                anyDouble(), anyLong(), anyString(), anyString());
        verify(semanticCache, never()).saveExactMatch(anyString(), anyString());
        verify(semanticCache, never()).saveReply(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(experienceService, never()).extractCommonExperience(anyString(), anyString(), anyString());
    }
}
