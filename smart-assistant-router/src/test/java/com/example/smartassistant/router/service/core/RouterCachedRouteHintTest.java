package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.RouteRequest;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.evaluation.BadCaseMinerService;
import com.example.smartassistant.router.service.evaluation.IntentGuidedQueryRewriter;
import com.example.smartassistant.router.service.experience.ExperienceService;
import com.example.smartassistant.router.service.guardrail.EmotionCheckResult;
import com.example.smartassistant.router.service.guardrail.GuardrailService;
import com.example.smartassistant.router.service.quality.QualityEvaluationService;
import com.example.smartassistant.router.service.rag.RouterRagService;
import com.example.smartassistant.router.service.routing.KeywordFastRouteService;
import com.example.smartassistant.router.service.taskanalysis.TaskAnalysisService;
import com.example.smartassistant.router.service.tool.RoutingToolChecker;
import com.example.smartassistant.common.intent.IntentTagGenerator;
import com.example.smartassistant.common.prompt.PromptManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouterCachedRouteHintTest {

    @Test
    void validatesConsumerHintAndMarksResultAsCacheHit() {
        RouteExecutionService execution = mock(RouteExecutionService.class);
        RouterService router = router(execution);
        RouteRequest request = new RouteRequest();
        request.setUserId(3L);
        request.setQuestion("popular products");
        request.setCachedAgentName("product");
        request.setCachedIntentTag("product_query");
        request.setCachedConfidence(0.9);
        RoutingResult dispatched = RoutingResult.builder()
                .agentName("product")
                .result("products")
                .fromCache(false)
                .build();
        when(execution.callAgentAndFinalize(
                "product", "popular products", 0.9, "product_query",
                request, "popular products", null)).thenReturn(dispatched);

        RoutingResult result = router.executeCachedRouteHint(request, "popular products", null);

        assertThat(result).isSameAs(dispatched);
        assertThat(result.getFromCache()).isTrue();
        verify(execution).callAgentAndFinalize(
                "product", "popular products", 0.9, "product_query",
                request, "popular products", null);
    }

    @Test
    void ignoresMissingHint() {
        RouteExecutionService execution = mock(RouteExecutionService.class);
        RouterService router = router(execution);
        assertThat(router.executeCachedRouteHint(new RouteRequest(), "question", null)).isNull();
    }

    private static RouterService router(RouteExecutionService execution) {
        ChatModel lightModel = mock(ChatModel.class);
        return new RouterService(
                mock(AgentCallerService.class), null, mock(RouterRagService.class),
                mock(IntentTagGenerator.class), mock(TaskPlannerService.class),
                mock(ResultMerger.class), mock(ReflectionService.class),
                mock(ExperienceService.class), mock(TaskAnalysisService.class),
                mock(QualityEvaluationService.class), mock(IntentGuidedQueryRewriter.class),
                mock(KeywordFastRouteService.class), mock(RoutingToolChecker.class),
                mock(DegradationService.class), mock(GuardrailService.class),
                mock(PromptManager.class), lightModel, mock(BadCaseMinerService.class),
                mock(RouteFinalizer.class), execution, mock(RouteContextHelper.class));
    }
}
